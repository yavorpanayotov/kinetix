# ADR-0037: Inter-Service Trust Model (mTLS vs NetworkPolicy)

## Status

Accepted

## Context

Kinetix is a multi-service platform where the API gateway validates JWTs from Keycloak (ADR-0013)
and forwards user identity to backend services via HTTP headers (`X-User-Id`, `X-User-Books`,
`X-User-Roles`). Backend services trust these headers without independently verifying the caller's
identity.

This creates a lateral movement risk: any process that can reach an internal service endpoint —
whether a compromised pod, a misconfigured sidecar, or an insider — can forge arbitrary user headers
and impersonate any principal. The threat is a prerequisite concern for institutional buyers undergoing
security review.

Two mitigations were considered:

**Option A — Kubernetes NetworkPolicy.** Restrict which pods can open TCP connections to which
other pods at the network layer. No change to application code; enforcement is in the CNI plugin.
*Parked.* The Kinetix runtime is docker-compose (not Kubernetes), so NetworkPolicy is not
available. The docker-compose equivalent — explicit bridge-network segmentation — prevents
external access but does not restrict lateral movement between containers sharing a bridge network.

**Option B — Mutual TLS (mTLS).** Each service presents a certificate signed by a shared CA when
opening a gRPC (or HTTP) connection. The server validates the client certificate before accepting
the connection. A compromised pod that does not hold a valid private key cannot establish a
session, regardless of what headers it sends.

`common/src/main/kotlin/com/kinetix/common/security/TlsConfig.kt` already contains scaffolding
for per-service TLS configuration (`TlsConfig`, `GrpcTlsConfig`). It is disabled by default.
The risk-orchestrator `Application.kt` already reads `GRPC_TLS_ENABLED` / `GRPC_TLS_CA` to
optionally enable one-way TLS toward the risk engine. The infrastructure for mTLS exists; this ADR
activates it with explicit defaults and cert paths.

## Decision

Enable **mutual TLS for service-to-service gRPC** with the following defaults:

| Profile (`KINETIX_PROFILE`) | mTLS default | Trust boundary |
|---|---|---|
| `prod` | **on** | Client certificate signed by shared CA required |
| anything else (incl. unset / `demo`) | off | Docker bridge network is the documented trust boundary |

Cert paths are well-defined and overridable via environment variables so that enabling mTLS in the
demo environment is a configuration change, not a code change:

| Purpose | Default path | Override env var |
|---|---|---|
| Service certificate (PEM) | `/etc/kinetix/certs/service-cert.pem` | `KINETIX_TLS_CERT` |
| Service private key (PEM) | `/etc/kinetix/certs/service-key.pem` | `KINETIX_TLS_KEY` |
| CA certificate (PEM) | `/etc/kinetix/certs/ca-cert.pem` | `KINETIX_TLS_CA` |

For server-side mutual auth, an additional path identifies the CA used to verify incoming client
certificates. In practice this is the same CA, so the default reuses `KINETIX_TLS_CA`.

**HTTP service-to-service** is deferred: Ktor's CIO client is not mTLS-capable without a custom
`SSLContext`, and the current HTTP calls (position-service, price-service, etc.) go through Nginx
in production which terminates TLS at the ingress. HTTP mTLS is left as future work and is noted
in the Implementation section.

**Cert rotation** and integration with cert-manager or Vault PKI are out of scope; this ADR
establishes the path, operational runbook to follow.

## Consequences

**Benefits.**

- A compromised pod without a valid client certificate cannot establish a gRPC session to any other
  Kinetix service in the `prod` profile. The attack surface for header-forgery is eliminated on the
  gRPC transport layer.
- Enabling mTLS in demo/staging is a single env-var flip (`KINETIX_PROFILE=prod` plus mounting
  certs), with no application rebuild required.
- The trust model is explicit and auditable: every gRPC channel either presents a cert or is
  plaintext; there is no ambiguous middle ground.

**Costs / risks.**

- Cert distribution must be solved before enabling prod profile. In docker-compose production, certs
  are mounted as bind-mounts or Docker secrets. In a future Kubernetes migration, cert-manager
  handles this automatically.
- One-way TLS toward the risk engine (existing `GRPC_TLS_ENABLED` path) is a subset of mTLS.
  Services wiring up mTLS must pass both `certPath`/`keyPath` (client cert) and `trustStorePath`
  (CA) — the existing `TlsChannelCredentials` path only handles the CA side. The new
  `TlsConfig.fromEnvironment()` and `GrpcClientMtlsExtensions` cover the full mTLS credential
  construction.
- HTTP service-to-service trust is not addressed by this ADR. The docker bridge network + Nginx
  TLS termination remain the trust boundary for HTTP until a follow-up ADR addresses it.

## Implementation

### New types in `common/src/main/kotlin/com/kinetix/common/security/`

**`GrpcServerTlsConfig.kt`** — server-side mTLS config (one type per file, per CLAUDE.md):

```kotlin
data class GrpcServerTlsConfig(
    val enabled: Boolean = false,
    val certPath: String? = null,
    val keyPath: String? = null,
    val clientCaPath: String? = null,  // CA used to verify client certificates (mutual auth)
) {
    companion object {
        fun fromEnvironment(): GrpcServerTlsConfig { /* reads KINETIX_PROFILE + KINETIX_TLS_* */ }
        fun forTesting(certPath: String, keyPath: String, clientCaPath: String): GrpcServerTlsConfig
        fun disabled(): GrpcServerTlsConfig = GrpcServerTlsConfig(enabled = false)
    }
}
```

**`GrpcServerMtlsExtensions.kt`** — `NettyServerBuilder.applyMtls(GrpcServerTlsConfig)` extension
that loads cert/key/clientCA from paths and configures `SslContextBuilder` with
`clientAuth = ClientAuth.REQUIRE`.

**`GrpcClientMtlsExtensions.kt`** — `ManagedChannelBuilder.applyMtls(TlsConfig)` extension
that builds `TlsChannelCredentials` with both server CA trust and client cert/key.

**`TlsConfig.fromEnvironment()`** — factory method added to the existing companion object.
Returns `forProduction(...)` when `KINETIX_PROFILE=prod`, otherwise `disabled()`.

### Cert paths at runtime (`prod` profile)

```
/etc/kinetix/certs/
  ca-cert.pem          # Shared CA certificate
  service-cert.pem     # This service's certificate, signed by the CA
  service-key.pem      # This service's private key (not shared)
```

Each service gets its own cert/key pair signed by the same CA. Wildcard or SAN certs covering
`*.kinetix.internal` are acceptable for small deployments.

### Test certs

Self-signed test certs live under `common/src/test/resources/certs/`:

```
certs/
  ca-cert.pem       # Test CA (self-signed, 10-year validity, for tests only)
  ca-key.pem        # Test CA private key
  server-cert.pem   # Server cert signed by test CA (CN=localhost)
  server-key.pem    # Server private key
  client-cert.pem   # Client cert signed by test CA (CN=kinetix-client)
  client-key.pem    # Client private key
```

These are checked into source control. They are test artifacts only — no production secrets.
Private keys in test resources are acceptable because they have no authority outside the test suite.

### Docker-compose demo trust boundary

In the demo profile, mTLS is off. The docker bridge network (`kinetix-net`) provides the trust
boundary: containers cannot be reached from outside the bridge without explicit port publication.
This is documented here and in `deploy/docker-compose.services.yml` comments as the explicit
security posture for the demo environment.

### Deferred: HTTP mTLS

HTTP calls between services (position-service, price-service, etc.) rely on Ktor's CIO HTTP
client. Enabling mTLS on the CIO client requires injecting a custom `SSLContext` built from the
service cert/key. In production, Nginx handles TLS termination and could enforce client
certificates at the ingress layer. This is left to a follow-up ADR once the gRPC path is
validated in production.
