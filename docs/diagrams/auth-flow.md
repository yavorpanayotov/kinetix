# Auth flow — JWT + service-principal

How identity flows from Keycloak login through the gateway to downstream services, including the service-principal pattern (ADR-0036) that keeps the original user as the principal of every AI tool call — so ACLs are enforced downstream and the audit trail stays honest. Consult this when touching auth, the gateway proxy headers, or per-book ACL enforcement.

```mermaid
sequenceDiagram
    actor U as User
    participant UI
    participant KC as Keycloak
    participant G as Gateway
    participant AI as AI Insights
    participant DS as Downstream service

    U->>UI: login
    UI->>KC: OIDC authorization-code flow
    KC-->>UI: JWT (sub + books claim)
    UI->>G: request + Bearer JWT
    G->>KC: validate signature via JWKS
    Note over G: mint correlationId (ADR-0022)
    G->>AI: proxy + X-User-Id, X-User-Books
    AI->>DS: MCP tool HTTP + X-User-Id, X-User-Books
    Note over DS: ACL enforced on ORIGINAL principal,<br/>not on ai-insights-service
    DS-->>AI: book-scoped data
    AI-->>G: SSE stream
    G-->>UI: response
```

Last regenerated: 2026-06-02 @ `c3ef7922`

Source signals: ADR-0013 (Keycloak auth & RBAC), ADR-0036 (service-principal `X-User-Id` / `X-User-Books`), ADR-0022 (correlation-id propagation), ADR-0012 (gateway aggregation), `gateway/Application.kt` (JwtConfig, BookAccessService, requirePermission).
