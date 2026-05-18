# ADR-0013: Keycloak for Authentication and RBAC

## Status
Accepted

## Context
The platform needs authentication and role-based access control for multiple user types: traders, risk managers, compliance officers, and administrators. Options: build custom auth, use Keycloak (open-source IAM), or use a cloud IAM provider.

## Decision
Use Keycloak as the identity provider, issuing JWTs that the gateway validates. Roles and permissions are modelled in a shared `common` module (`SecurityModels.kt`) to ensure consistency across services.

**Roles:** `ADMIN`, `TRADER`, `RISK_MANAGER`, `COMPLIANCE`, `VIEWER`

**Permission model:** Each role maps to a fixed set of permissions (e.g., `READ_PORTFOLIOS`, `WRITE_TRADES`, `CALCULATE_RISK`, `PROMOTE_EOD_RUN`). The `UserPrincipal` data class provides `hasPermission()` and `hasRole()` methods for authorization checks.

**Gateway enforcement:** The gateway validates JWTs via `ktor-server-auth-jwt`, extracting `subject`, `preferred_username`, and `roles` claims from the Keycloak token. Route groups are wrapped in `requirePermission()` blocks that enforce access before the request reaches a backend service.

## Applies when
- Adding a new gateway route — every route needs an explicit permission.
- Adding a new permission or role.
- Tempted to call `SecurityModels` from a backend service directly, or to re-validate JWTs deeper than the gateway.

## Rules
- **DO** wrap every gateway route group in `requirePermission(Permission.XXX)`. There is no implicit allow.
- **DO** add new `Permission` and `Role` values in `common/.../SecurityModels.kt`. One enum, one source of truth.
- **DO** propagate the caller's `UserPrincipal` to backend services via the existing forwarded-identity header pattern when audit logging requires it.
- **DO** assign permissions to roles in `RolePermissions` map. Update the Keycloak realm export (`deploy/keycloak/realm-export.json`) when adding new roles.
- **DON'T** invent a parallel permission model in a backend service. If you need a finer-grained check, add a `Permission` to the shared enum and enforce at the gateway.
- **DON'T** validate JWTs in backend services — they trust the gateway. Backend services run inside the cluster, not exposed externally.
- **DON'T** store user passwords, MFA secrets, or refresh tokens anywhere in Kinetix. Keycloak owns identity.
- **DON'T** hardcode role names as strings. Use the `Role` enum.

## Consequences

### Positive
- Centralised identity management — user provisioning, password policies, and MFA handled by Keycloak
- Standard JWT flow — any service can independently verify tokens without calling the IdP
- Permission model in shared `common` module ensures role definitions are consistent across all services
- The gateway enforces RBAC at the edge — backend services trust the gateway's forwarded identity

### Negative
- Keycloak is an additional infrastructure component to deploy and maintain
- JWT validation adds latency to every authenticated request (mitigated by local verification with cached public keys)
- Role-permission mapping is hardcoded in Kotlin — changes require a code deployment (acceptable for a controlled internal system)

### Alternatives Considered
- **Custom auth service**: Full control, but reimplements solved problems (token issuance, refresh, revocation, MFA).
- **Cloud IAM (Auth0, AWS Cognito)**: Managed, but adds external dependency and cost. Keycloak provides the same capabilities self-hosted.
- **mTLS only**: Handles service-to-service auth but not user identity or fine-grained permissions.
