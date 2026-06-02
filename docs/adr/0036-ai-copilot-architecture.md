# ADR-0036: AI Copilot Architecture (v2)

## Status
Accepted (2026-05-19)

## Related specs
- [`specs/ai-insights.allium`](../../specs/ai-insights.allium) — chat SSE contract and history, citation verification and banned-phrase policy guard, in-process MCP read-tool registry, canned-vs-Claude chat-client switch.

## Context

AI v1 shipped two narrow explainers (VaR Explainer, Report Commentary) that demoed well but were not load-bearing for traders. The v2 product goal — *"showcase AI"* — only succeeds if the feature is something a trader opens Kinetix for daily. The trader review by *Marcus* (senior FX/rates desk) was unambiguous: the headline must be **proactive push**, not chat. *"Kinetix tells you what changed and why, before you ask."*

v2 builds on that verdict with four user-visible surfaces:

1. **Morning brief** — lands at 06:45 with the overnight risk story (sourced, numeric, dismissible).
2. **Intraday push** — alerts when thresholds breach (limit utilisation, vol inversions, correlation breaks, unexplained P&L, regime changes).
3. **Inline explainers** — anchored to every data row that matters (VaR, positions, P&L attribution, alerts, scenarios, Greeks, correlation).
4. **⌘K command palette** — the free-form escape hatch for ad-hoc questions.

All four surfaces share the same underlying machinery: a Claude Code SDK conversation grounded in Kinetix's own data via an in-process MCP server. **Write actions are explicitly out of scope** — AI booking trades is a career-ending feature.

v2 keeps the v1 auth model: the host's `~/.claude/` Claude Code subscription, no `ANTHROPIC_API_KEY`. Live in dev/demo, canned in CI.

This ADR records the architecture decisions that the plan in `docs/plans/ai-v2.md` is built on, so subsequent PRs can refer to a single source of truth rather than re-litigating them per checkbox.

## Decision

The v2 Copilot is implemented as new modules inside the existing `ai-insights-service` (FastAPI / Python). No new service is added — chat endpoint, morning-brief generator, intraday-push generator, and saved-query runner all live alongside the v1 explainers in the same app. The gateway gets new streaming-proxy plumbing and one new WebSocket route; everything else is internal.

### In-process MCP server (internal port 8096)

The MCP (Model Context Protocol) server runs **in-process** inside `ai-insights-service` on internal port 8096, bound to the cluster network only and **not exposed via the gateway**. Tools are registered at app startup, the SDK's `query()` invokes them over the local MCP transport, and tool implementations call downstream Kinetix services over HTTP.

Rationale:

- An in-process server means tool calls do not cross a network hop between the SDK and the tool, eliminating one source of latency on the hot path.
- The MCP surface is **not** a public API — there are no third-party consumers in v2. Exposing it externally would create needless attack surface (every tool becomes a potential prompt-injection sink) without unlocking any v2 use case.
- Keeping the server in-process means the SDK, the tools, and the policy/citation guards all share the same Python process and the same request-scoped `UserContext`, which makes per-user audit logging and ACL enforcement straightforward.

Operationally, port 8096 is declared in the `ai-insights-service` container in Docker Compose and the Helm chart, but it is bound to the pod's internal interface and is not surfaced through the gateway's reverse proxy.

### Service-principal auth (JWT → `X-User-Id` / `X-User-Books`)

Outbound MCP tool calls from `ai-insights-service` to downstream Kinetix services (position-service, risk-orchestrator, price-service, volatility-service, correlation-service, audit-service, notification-service, reference-data-service) use a **service-principal pattern**:

1. The gateway validates the user's JWT (ADR-0013 / Keycloak) on the inbound HTTP/SSE/WebSocket request.
2. The gateway extracts `sub` and the `books` claim (or its ACL equivalent) and writes them to `X-User-Id` and `X-User-Books` (comma-separated list) headers as it proxies to `ai-insights-service`.
3. `ai-insights-service` constructs a `UserContext` dataclass for the request and stamps `X-User-Id` / `X-User-Books` onto every downstream call via the `KinetixHttpClient` abstraction.
4. Every audit log entry written by `ai-insights-service` carries `user_id` from the same `UserContext`.

This means downstream services see the original user as the principal of every tool call, **not** the `ai-insights-service` itself. ACL enforcement (e.g. "this trader can only see books they're scoped to") happens at the downstream service, not in the AI layer — the AI is just another HTTP client. Per-tool checks (e.g. `get_book_var` failing closed when the requested book is not in `X-User-Books`) provide a second layer in the AI layer itself.

Rationale:

- Avoids re-implementing ACL in the AI layer. Books-the-user-can-see is already a downstream service concern.
- Keeps the audit trail honest: a query on book `FX-EUR-1` initiated by trader `alice` shows `user_id=alice` in position-service logs, not `user_id=ai-insights-service`.
- Keeps the AI service stateless w.r.t. identity — every request carries its own principal.

Per-user Claude billing/quota is **out of scope** for v2 (single host credential, see *Credential multi-tenancy* below).

### SSE for chat, morning brief, and saved-query runs

Server-Sent Events (SSE) is the streaming transport for:

- `POST /api/v1/insights/chat` — multi-turn chat, token-by-token streaming.
- `POST /api/v1/insights/queries/{id}/run` — saved-query execution (reuses the chat client under the hood).
- `GET /api/v1/insights/brief/today` — the morning brief is non-streaming on first read but the brief generator emits SSE during generation for the dashboard's progressive render.

Frame contract: `data: {ChatChunk}\n\n` lines for content, `event: source` frames for citations as they are produced, final frame `done: true` with `session_id`, `conversation_id`, `model`, `mode`.

Rationale (SSE vs WebSocket for chat):

- SSE is **unidirectional server-to-client**, which is exactly the chat-response shape. The client speaks once (the prompt POST) and listens for many chunks. SSE matches.
- SSE is **browser-native** (`EventSource`) with built-in auto-reconnect and `Last-Event-ID` resumption semantics. No custom client-side reconnect logic required.
- SSE travels over plain HTTP/1.1 + chunked transfer, so it passes through every existing HTTP middleware (auth, rate-limit, tracing) without special-casing. WebSockets need every middleware re-implemented.
- SSE is **simpler to multiplex** in tests — `httpx.AsyncClient(stream=True)` against `TestClient` works exactly like a normal HTTP request.

The gateway picks up the SSE stream from `ai-insights-service` via a new `streamProxyToInsights` function that uses `HttpStatement.execute {}` + `respondBytesWriter` to pipe the SSE bytes byte-for-byte. HTTP client timeout for the streaming route family is set to `Long.MAX_VALUE` so the gateway does not cut long-running streams. The existing `proxyToInsights` stays unchanged for v1 non-streaming endpoints.

### WebSocket `/ws/copilot` for intraday push

Intraday push uses a new gateway WebSocket route `/ws/copilot`, paired with a new `CopilotBroadcaster` in the gateway that mirrors the existing `AlertBroadcaster` pattern (ADR-0016). The flow:

1. Kafka consumer in `ai-insights-service` (group `ai-insights-risk-consumer`) reads `risk.results` and `risk.regime.changes`.
2. `IntradayThresholdEvaluator` compares each event against per-user/per-book thresholds (table `copilot_alert_thresholds`).
3. When a threshold fires, `IntradayPushGenerator` composes a sourced push payload.
4. `ai-insights-service` POSTs the payload to the gateway's new internal route `POST /internal/copilot/push` (cluster-internal only; no external exposure, no JWT challenge).
5. The gateway enqueues the payload to `CopilotBroadcaster`, which fan-outs to subscribed WebSocket clients filtered by `X-User-Books` scope.

Rationale (WebSocket vs SSE for push):

- Push is **server-initiated**, multiplexed across many users, with **long-lived per-user connections**. WebSockets are the existing pattern (`AlertBroadcaster`) and mirroring it keeps operational/observability surface area small.
- Each user maintains one persistent WebSocket; the broadcaster fan-outs in-process. SSE would require per-user long-poll connections kept open against `ai-insights-service`, which is a less natural fit for a notification fan-out topology.
- The browser only needs **one** WebSocket connection regardless of how many push types exist; messages are typed and dispatched client-side.

### Conversation state model

`ConversationStore` is a Python protocol with two implementations:

- `InMemoryConversationStore` — `OrderedDict` + timestamp eviction, **24-hour TTL**. Ships in v2.
- `RedisConversationStore` — Redis-backed, same protocol, selected when `REDIS_URL` env is set. Hardening item near the end of the plan.

Each conversation is keyed by `conversation_id` (server-issued UUID, returned in the first SSE frame and echoed back by the client on subsequent turns). State holds the prior turn history so the SDK has multi-turn context. No persistent multi-session memory — each conversation is **disposable** after 24 hours. Saved queries cover the "remember my favourite question" use case (see PR 8).

Rationale:

- In-memory ships in days; Redis ships in weeks (Testcontainers, deployment, ops). Decoupling via the protocol means the v2 codebase is Redis-ready without paying the Redis tax up front.
- 24h TTL matches a trading day — long enough for "I'll come back after lunch", short enough that we never accumulate a chat history database.
- No persistent multi-session memory is **explicit policy** — see "Out of scope" in `docs/plans/ai-v2.md`. Persistent memory was rejected as a v2 surface; reconsider only after v2 ships and we have data.

### Citation contract & policy guards

Every numeric token in any AI response carries a machine-readable `Citation` object:

```python
class Citation(BaseModel):
    tool: str                  # e.g. "get_book_var"
    params: dict[str, Any]     # e.g. {"book_id": "FX-EUR-1"}
    result_field: str          # e.g. "total_var"
    result_value: float
    result_currency: str | None
    as_of_timestamp: datetime
    data_source: str           # e.g. "risk-orchestrator/valuation_jobs"
    freshness_seconds: int     # tool-level SLA
    quality_flags: list[str]   # e.g. ["stale", "interpolated"]
```

The model is **prompt-instructed** never to state a number without a citation. A post-generation `citation_verifier` tokenises the narrative with `\$?[\d,]+(?:\.\d+)?%?` (handles JPY no-decimal and BHD three-decimal) and matches each numeric token against `citation.result_value`. Uncited tokens are tagged `CITATION_UNVERIFIABLE`.

Policy guards run server-side, not by relying on model behaviour:

- **Banned-phrase regex** — patterns like `\b(you should|i recommend|consider hedging|consider reducing|you might want to|my advice|i suggest|you ought to|verify with your team|please confirm with)\b` return `POLICY_VIOLATION`. These exist because *Marcus* explicitly rejected verification-hedge copy and any whiff of advice.
- **Ticker / counterparty verification** — tokens that look like identifiers but do not appear in any tool result for the conversation are flagged `CITATION_UNVERIFIABLE`. Counterparty name resolution uses a fuzzy-match utility (`resolve_counterparty`) that returns "not found" rather than guessing.

These are belt-and-braces over model prompting: the prompt asks for citations and bans the phrases, but enforcement happens on the way out.

### Demo mode

Every new endpoint has a `Canned*Client` variant that emits the same SSE frame shape from a fixture file under `ai-insights-service/src/kinetix_insights/fixtures/`. `DEMO_MODE=true` selects canned at factory time. The factory is the single decision point — endpoints do not branch on the env var themselves.

CI **never reaches the live SDK**. A pytest fixture raises if `DEMO_MODE != "true"` in CI, which makes accidental live-SDK calls a hard test failure rather than a quiet credit burn.

Rationale:

- Canned mode is what the 90-second demo runs on. The fixture transcripts are deterministic and reviewable.
- The same canned client is reused by Playwright (route mocked at the gateway) — fixtures are shared between Python and TypeScript test layers.
- CI safety: the host's `~/.claude/` credential should never appear in CI logs / telemetry / billing.

### Latency budget

- **3 s to first useful content** end-to-end (user-perceived).
- **500–800 ms per tool call uncached.**
- `run_scenario` and `cross_book_exposure` may bust the per-tool budget; both must hit a precomputed result (existing `risk_hierarchy_snapshots`, named-scenario cache). Ad-hoc cross-book / scenario queries that need cold computation are not in scope for v2.

Service overhead from request submit to first SSE chunk is asserted in the chat-latency performance test (`tests/test_chat_latency.py -m performance`) at <500 ms with a `_FakeStreamingSdk` that yields the first chunk at 0 ms. The UI separately asserts a 3-second first-useful-content budget in Playwright (`ui/e2e/copilot-latency.spec.ts`).

### Credential multi-tenancy (single `~/.claude/` subscription)

v2 uses a **single host subscription** — the `~/.claude/` Claude Code credential on the box running `ai-insights-service`. There is no `ANTHROPIC_API_KEY`, no per-user OAuth, no per-user billing.

Implications:

- **Single shared rate-limit pool.** All Kinetix users share the host subscription's rate limit. The gateway applies a per-user rate limit (10 req / user / minute) on `/api/v1/insights/chat` and `/api/v1/insights/queries/*/run` keyed by JWT `sub`, returning 429 on breach. This protects the shared pool from any single user burning it.
- **Cannot isolate cost per user.** Billing for v2 is at the subscription level; per-user attribution is best-effort via structured audit logs (`tokens_estimated` is recorded but not chargeable).
- **Cross-user isolation relies on SDK statelessness.** Each `query()` call is independent — there is no Claude-side session that could leak context across users. Our `ConversationStore` is per-`conversation_id` and never reused across users. An integration test (`tests/test_chat_user_isolation_integration.py`) stands up two stub backends scoped to different users and asserts no cross-contamination.
- **Audit per user, isolate per session.** Every chat / brief / query / push call writes a structured log line with `user_id`, `endpoint`, `prompt_hash`, `tool_calls[]`, `tokens_estimated`, `mode`, `latency_ms`, `timestamp`. We can reconstruct who ran what against the shared credential.

Per-user Claude billing / OAuth is **explicitly deferred**; see *Alternatives considered* below.

## Applies when

- Adding a new AI-driven endpoint, surface, or capability to `ai-insights-service`.
- Adding a new MCP tool (must follow the citation contract, freshness SLA, and `KinetixHttpClient` pattern).
- Touching gateway proxy plumbing for AI routes (streaming vs non-streaming, JWT-to-header bridging, `/ws/copilot`, `/internal/copilot/push`).
- Considering exposing the MCP server externally, persisting chat history beyond 24h, adding write actions, or introducing per-user OAuth.

## Rules

- **DO** keep the MCP server in-process and internal-only (port 8096, not gateway-exposed).
- **DO** call downstream services via `KinetixHttpClient` so every request carries `X-User-Id` / `X-User-Books`.
- **DO** stream chat / saved queries / brief via SSE; use WebSocket only for the `/ws/copilot` intraday push channel.
- **DO** depend on the `ConversationStore` protocol — never on a concrete impl — so the in-memory ↔ Redis swap is a one-line change.
- **DO** populate a `Citation` for every numeric token, and rely on `citation_verifier` + `policy_guard` to enforce on the way out.
- **DO** ship every new endpoint with a `Canned*Client` and select it via `DEMO_MODE=true`.
- **DO** apply the per-user gateway rate limit (10 req / user / minute) to any new streaming AI route.
- **DON'T** expose the MCP server via the gateway. It is not a public API.
- **DON'T** add write actions of any kind (booking, hedging, limit adjustment) — out of scope; revisit only after read-side is bulletproof and ACL'd.
- **DON'T** produce hedging recommendations, free-form macro views, or verification-hedge copy. The `policy_guard` regex enforces this.
- **DON'T** persist chat across sessions beyond the 24h TTL. Saved queries cover the "remember my question" use case.
- **DON'T** reach the live SDK from CI. Use the canned client; the pytest fixture enforces this.
- **DON'T** mint `X-User-Id` / `X-User-Books` anywhere except the gateway's JWT-to-header bridge.

## Consequences

### Positive

- **No API key plumbing.** The host's `~/.claude/` subscription means no secret rotation, no per-user OAuth flow, and no `ANTHROPIC_API_KEY` in environment variables — same model as v1, so v2 inherits the operational simplicity.
- **Fast to ship.** Reusing `ai-insights-service` (no new module/service) keeps the surface area small. The in-process MCP server avoids a second deployable. Canned mode means the demo lands before the live SDK integration is bulletproof.
- **Citation contract is the trust anchor.** Every number is sourced; the verifier flags uncited tokens before they reach the user. This is the answer to the trader's "I don't trust AI-generated numbers" objection — it is structurally impossible to produce an uncited number without it being flagged.
- **Service-principal auth keeps ACL where it already lives.** Downstream services already enforce per-book ACLs on REST endpoints; the AI layer is just another caller. No ACL duplication.
- **Demo mode + CI fixture means we ship the demo without burning credits.** CI is deterministic, fast, and never touches a paid endpoint.

### Negative

- **Single shared rate-limit pool.** All users share one host subscription. A burst from a few users can exhaust the pool for everyone. Mitigation: gateway rate limit per user (10 req/min); monitoring via `copilot_sdk_error_total` (rate-limit subset); operational runbook for when the pool is exhausted.
- **Cannot isolate cost per user.** v2 cannot say *"trader X cost us $Y in Claude tokens this month"* — billing is at the subscription level. Mitigation: `tokens_estimated` in audit logs lets us reconstruct per-user usage if/when this becomes a chargeable concern.
- **Cross-user isolation depends on SDK statelessness.** If a future SDK release introduces server-side session affinity, that becomes a security issue. Mitigation: integration test asserts no cross-contamination today; we re-verify on SDK upgrades.
- **In-process MCP means the SDK and the tools share a process.** A tool that hangs (e.g. a downstream service timeout not honoured) can block the SDK. Mitigation: every tool call has a timeout; tool timeouts surface as citation entries with `status: timeout` rather than hanging the chat stream.
- **WebSocket push fan-out is gateway-bound.** A gateway restart drops all WebSocket connections; clients reconnect via `useCopilotWebSocket()` backoff. This is the same trade-off `AlertBroadcaster` already lives with.

## Alternatives considered

- **HTTP gateway-exposed MCP server.** Rejected. Exposing tools externally creates a third-party attack surface (every tool becomes a prompt-injection sink), and v2 has no external consumers. If a future product line needs externally-callable tools, we revisit then — but starting external would mean per-tool input validation, per-tool auth, per-tool rate limiting, all unneeded for v2's use case.
- **WebSocket for chat too.** Rejected. SSE is simpler for unidirectional streaming, browser-native (`EventSource`), supports auto-reconnect natively, passes through HTTP middleware unchanged, and is straightforward to test. WebSocket would have required us to re-implement reconnection, message framing, and middleware integration for no streaming-shape benefit.
- **Per-user OAuth + per-user Claude billing.** Deferred. Solving this needs (a) Claude OAuth integration, (b) per-user credential storage, (c) ACL hardening so we are confident the AI layer cannot exfiltrate across users, and (d) a billing model. v2 ships against a shared subscription; revisit when the business case for per-user billing is concrete.
- **Persistent multi-session memory.** Rejected. Explicit policy: saved queries cover the "remember my favourite question" use case. Persistent memory introduces a *what does it remember about me* question with no clear answer and a privacy footprint we are not ready to take on in v2.
- **Separate `ai-copilot-service` instead of extending `ai-insights-service`.** Rejected. New service = new deployment unit, new Helm chart, new Postgres allocation, new on-call surface — none of which v2 needs. v1's `ai-insights-service` is the right home; v2 grows it.
- **Direct WebSocket from `ai-insights-service` (skip gateway).** Rejected. Mirroring `AlertBroadcaster`'s gateway-broadcast pattern (ADR-0016) means JWT auth, scope filtering, and operational tooling all stay in one place. Bypassing the gateway would duplicate auth.
- **Use a vendor agent framework (LangChain, AutoGen, etc.).** Rejected. Adds a dependency surface without solving the citation contract (which is custom) or the policy guards (which are custom). The Claude Code SDK + a thin MCP layer is the minimum that works.
- **Persist chat in Postgres rather than Redis.** Rejected for v2 (in-memory ships now, Redis is the hardening step). Postgres is overkill for a 24h-TTL conversation cache and would couple AI lifecycle to a relational migration.

## References

- `docs/plans/ai-v2.md` — execution plan with checkboxes derived from this ADR.
- ADR-0012 — API Gateway Aggregation Pattern (where streaming proxy and `/internal/copilot/push` route fit).
- ADR-0013 — Keycloak for Authentication (JWT source for `X-User-Id` / `X-User-Books`).
- ADR-0016 — WebSocket for Real-Time UI Updates (the `AlertBroadcaster` pattern that `CopilotBroadcaster` mirrors).
- ADR-0017 — Hash-Chained Audit Trail (every AI tool call is auditable per user).
- ADR-0008 — Grafana Stack for Observability (`copilot_*` metrics namespace, Loki audit logs, Tempo trace propagation).
- ADR-0022 — Correlation ID Propagation (gateway → `ai-insights-service` → downstream services).
