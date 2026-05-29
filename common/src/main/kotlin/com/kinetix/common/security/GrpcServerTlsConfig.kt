package com.kinetix.common.security

/**
 * Server-side mTLS configuration for a Netty gRPC server.
 *
 * When [enabled] is true, the server presents [certPath]/[keyPath] to clients and
 * requires incoming client certificates to be signed by the CA at [clientCaPath]
 * (mutual TLS, ClientAuth.REQUIRE).
 *
 * When [enabled] is false the server accepts plaintext connections — appropriate for
 * the docker-compose demo environment where the bridge network is the trust boundary
 * (ADR-0037).
 *
 * See [TlsConfig] for the client-side counterpart.
 */
data class GrpcServerTlsConfig(
    val enabled: Boolean = false,
    val certPath: String? = null,
    val keyPath: String? = null,
    val clientCaPath: String? = null,
) {
    companion object {

        /**
         * Reads [KINETIX_PROFILE] from the environment. Returns a production-grade
         * config when the profile is "prod", otherwise returns [disabled].
         *
         * Production cert paths are overridable via:
         *  - KINETIX_TLS_CERT   → certPath    (default /etc/kinetix/certs/service-cert.pem)
         *  - KINETIX_TLS_KEY    → keyPath     (default /etc/kinetix/certs/service-key.pem)
         *  - KINETIX_TLS_CA     → clientCaPath (default /etc/kinetix/certs/ca-cert.pem)
         */
        fun fromEnvironment(): GrpcServerTlsConfig {
            return when (System.getenv("KINETIX_PROFILE")) {
                "prod" -> GrpcServerTlsConfig(
                    enabled = true,
                    certPath = System.getenv("KINETIX_TLS_CERT") ?: "/etc/kinetix/certs/service-cert.pem",
                    keyPath = System.getenv("KINETIX_TLS_KEY") ?: "/etc/kinetix/certs/service-key.pem",
                    clientCaPath = System.getenv("KINETIX_TLS_CA") ?: "/etc/kinetix/certs/ca-cert.pem",
                )
                else -> disabled()
            }
        }

        /**
         * Constructs a server mTLS config suitable for unit/acceptance tests.
         * All three paths must point to PEM files accessible from the test classpath
         * or filesystem.
         */
        fun forTesting(certPath: String, keyPath: String, clientCaPath: String): GrpcServerTlsConfig =
            GrpcServerTlsConfig(
                enabled = true,
                certPath = certPath,
                keyPath = keyPath,
                clientCaPath = clientCaPath,
            )

        /** Returns a disabled config — mTLS off, plaintext only. */
        fun disabled(): GrpcServerTlsConfig = GrpcServerTlsConfig(enabled = false)
    }
}
