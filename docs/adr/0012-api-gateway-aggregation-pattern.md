# ADR-0012: API Gateway Aggregation Pattern

## Status
Accepted

## Context
The UI needs data from multiple backend services (positions, prices, risk, notifications, regulatory, audit). Exposing each service directly to the frontend creates coupling, complicates CORS, and pushes aggregation logic into the browser.

## Decision
Use a dedicated API gateway service (`gateway/`) that aggregates all backend services behind a single HTTP endpoint for the UI. The gateway is a stateless Ktor application that proxies and composes responses from backend services via typed HTTP client interfaces.

Backend service clients are defined as interfaces (`PositionServiceClient`, `PriceServiceClient`, `RiskServiceClient`, `NotificationServiceClient`, `RegulatoryServiceClient`) with `Http*` implementations using Ktor's HTTP client. This allows mock implementations in tests.

The gateway also hosts:
- WebSocket endpoint for real-time price streaming (`PriceBroadcaster`)
- JWT authentication and RBAC enforcement
- OpenAPI/Swagger documentation
- System health aggregation (`/api/v1/system/health` — fans out health checks to all 10 backend services)

## Applies when
- Exposing a backend feature to the UI.
- Adding a new HTTP route that the browser will call.
- Tempted to call `position-service`, `risk-orchestrator`, or any other backend service directly from the UI.

## Rules
- **DO** add every UI-facing route to `gateway/` and proxy to backend services via the typed client interfaces (`PositionServiceClient`, `RiskServiceClient`, etc.).
- **DO** define new backend clients as interfaces in `gateway/.../clients/` with `Http*` implementations. Tests substitute fakes.
- **DO** enforce auth at the gateway via `requirePermission(...)` wrappers (ADR-0013). Backend services trust the gateway's forwarded identity.
- **DO** put aggregation logic (cross-service composition, fan-out, response shaping) in the gateway, not the UI.
- **DO** keep the gateway stateless — no per-user state, no session storage. WebSockets are the only stateful aspect.
- **DON'T** expose backend services to the browser. The UI's CORS allowlist names the gateway origin only.
- **DON'T** add business logic to the gateway beyond response shaping and auth. Heavy logic belongs in the owning backend service.
- **DON'T** call a backend service's DB or Kafka directly from the gateway — go through the service's HTTP API.

## Consequences

### Positive
- Single entry point for the UI — one base URL, one CORS configuration
- Aggregation happens server-side with low-latency internal calls instead of client-side with multiple round trips
- Backend services remain unaware of UI concerns (response shaping, auth token validation)
- Client interfaces enable comprehensive gateway testing with mock backends

### Negative
- Gateway is an additional service to deploy and monitor
- Request latency includes an extra hop (UI → gateway → backend service)
- Gateway must be updated when new backend endpoints are added

### Alternatives Considered
- **Direct service-to-UI calls**: Simpler architecture, but the UI would need to manage multiple base URLs, CORS for each service, and client-side aggregation. Authentication would need to be implemented in every service.
- **API mesh / service mesh (Envoy, Istio)**: Handles routing and mTLS, but doesn't provide response aggregation or business-level composition. Too heavy for the current scale.
- **BFF (Backend for Frontend)**: Conceptually similar; our gateway is effectively a BFF for the React UI.
