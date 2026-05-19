# Kinetix Copilot — AI v2

## Context

AI v1 shipped two narrow explainers (VaR Explainer, Report Commentary) — pretty for a demo but not the kind of thing a trader opens Kinetix for daily. The product goal of "showcase AI" needs a feature that traders actually use, not another chatbot. v2 builds that feature.

The trader's verdict (review by *Marcus*, senior FX/rates desk): the headline is **proactive push**, not chat. *"Kinetix tells you what changed and why, before you ask."* A morning brief lands at 06:45 with the overnight risk story (sourced, numeric, dismissible); intraday alerts fire when thresholds breach; inline explainers anchor to every data row that matters; ⌘K is the free-form escape hatch. Read tools over Kinetix's own data via an in-process MCP server are what makes any of this answerable. **Write actions are out of scope** — AI booking trades is a career-ending feature.

v2 keeps the v1 auth model: the host's `~/.claude/` Claude Code subscription, no `ANTHROPIC_API_KEY`. Live in dev/demo, canned in CI.

## Status

This plan is loop-ready for `/work-plan`. Each `- [ ]` checkbox below is one independently-committable change, ordered top-to-bottom by dependency, with an `Acceptance:` command on the line directly after it. Advance end-to-end with `/loop /work-plan plans/ai-v2.md`.

## Decisions applied

- **Service topology** — extend `ai-insights-service`. No new services. MCP server runs in-process on internal port 8096 (not exposed via gateway). Chat endpoint, morning-brief generator, intraday-push generator, and saved-query runner are all routes/modules in the same FastAPI app.
- **Auth model** — host's `~/.claude/` subscription (no `ANTHROPIC_API_KEY`). MCP tool calls to downstream Kinetix services use a service-principal pattern: gateway extracts the user's JWT `sub` claim and forwards `X-User-Id`/`X-User-Books`; `ai-insights-service` stamps these into every downstream request and into every audit log entry. Per-user Claude billing/quota is out of scope for v2 (single host credential, rate-limited at the gateway).
- **Streaming transport** — Server-Sent Events (SSE) for `/chat`, `/queries/{id}/run`, and the morning-brief stream. WebSocket `/ws/copilot` (new gateway route) for intraday push.
- **Conversation state** — `ConversationStore` protocol; `InMemoryConversationStore` ships in v2; `RedisConversationStore` is the hardening item at the end of the plan. TTL 24h.
- **Citation contract** — every numeric token in any AI response carries a machine-readable `Citation` object: `{tool, params, result_field, result_value, result_currency, as_of_timestamp, data_source, freshness_seconds, quality_flags}`. The model is prompt-instructed never to state a number without a citation; a post-generation `citation_verifier` flags uncited numeric tokens as `CITATION_UNVERIFIABLE`.
- **Hallucination & policy guards** — banned-phrase regex (e.g. `\b(you should|i recommend|consider hedging|verify with your team|i suggest)\b`) returns `POLICY_VIOLATION`. Ticker/counterparty tokens are verified against tool results; unknown tokens trigger `CITATION_UNVERIFIABLE`. These are enforced server-side, not by relying on model behaviour.
- **Demo mode** — every new endpoint has a `Canned*Client` that emits the same SSE frame shape from a fixture file under `ai-insights-service/src/kinetix_insights/fixtures/`. `DEMO_MODE=true` selects canned at factory time. CI never reaches the live SDK; a pytest fixture raises if `DEMO_MODE != "true"` in CI.
- **Latency budget** — 3 s to first useful content. Per-tool budget: 500–800 ms uncached. `run_scenario` and `cross_book_exposure` may bust; both must hit a precomputed result (existing `risk_hierarchy_snapshots`, named-scenario cache).
- **Cross-book scope (front-office)** — a trader can only query books their JWT scopes them to. Risk-manager cross-book queries are out of scope for v2 (defer until ACL review).
- **Data infra additions (minimal)** — two new tables: `limit_breach_events` (position-service) for breach history; `copilot_alert_thresholds` (risk-orchestrator) for per-user/per-book thresholds. All other data-analyst recommendations (cross_book_factor_exposure materialised view, daily_price_moves, settlement_date column, fx_rate_history, prices partitioning, instruments.attributes enforcement) are out of scope for v2.
- **Inline explainer surfaces in v2** — VaR dashboard (refactor existing button), Positions table (per row + header), P&L attribution chart, Alerts/breaches panel, Stress/Scenarios panel, Greeks panel, Correlation matrix. Skip: trade blotter rows, raw price ticks, settings, reference-data screens.
- **UI components** — extract reusable `<ExplainButton>`, `<StreamingNarrative>`, `<CitationFootnote>`, `<CitationList>`, `<NotificationStrip>`, `<MorningBriefCard>`, `<SavedQueryChip>`. Extend `<CommandPalette>` with `copilotMode` — do not fork. Keep `<AIInsightPanel>` for non-streaming/canned reuse and refactor it to compose `<StreamingNarrative>` when streaming.
- **Saved queries** — store in `localStorage` keyed by `kinetix:copilot:saved-queries`. 12-max user-saved, plus 5 built-in defaults (Lock icon, undeletable). No server table.
- **TDD discipline** — failing test + minimal implementation land in the same commit (matches v1 PR practice). Every commit must build green.
- **New ADR** — ADR-0036: AI Copilot Architecture, drafted alongside PR 1.

## CI/CD & guardrail approvals (pre-approved)

Per CLAUDE.md, the following cross architecture / dependency / schema lines and are pre-approved here so subagents don't stop mid-loop:

- **New Python dependencies in `ai-insights-service/pyproject.toml`**: `mcp`, `aiokafka`, `redis` (async client), `httpx` (already present), `prometheus-client`. Approved.
- **New gateway route prefix**: `/ws/copilot` WebSocket with a new `CopilotBroadcaster` (mirrors existing `AlertBroadcaster`). Approved.
- **New gateway internal route**: `POST /internal/copilot/push` — cluster-internal only, not exposed externally. Threat model documented in ADR-0036. Approved.
- **Streaming proxy change in gateway**: new `streamProxyToInsights` function alongside existing `proxyToInsights`. HTTP client timeout for streaming routes set to `Long.MAX_VALUE`. Approved.
- **MCP server in-process on port 8096**: internal-only bind, exposed in Docker Compose and Helm chart for the `ai-insights-service` container. Approved.
- **New Kafka consumer group**: `ai-insights-risk-consumer` on existing topics `risk.results` and `risk.regime.changes`. No new topic. Approved.
- **New DB tables**: `limit_breach_events` (position-service) and `copilot_alert_thresholds` (risk-orchestrator). Flyway migrations. Approved.
- **New ADR**: `docs/adr/ADR-0036-ai-copilot-architecture.md`. Approved.
- **No CI/CD pipeline file changes anticipated** (`.github/workflows/*`). If a subagent discovers it must touch one, STOP and flag.

## Out of scope

Hard exclusions for v2 — the trader's no-go list plus things explicitly deferred:

- **Write actions of any kind.** No "execute this hedge", no "submit this order", no "adjust this limit", no trade-booking via AI. Compliance/regulatory territory; revisit only after read-side reads are bulletproof and ACL'd.
- **Hedging recommendations / portfolio advice.** "You should hedge X" is forbidden output. Decision support only: state the exposure, list options with carry cost; never recommend an action.
- **Free-form macro views.** "What will the Fed do?" — out. The AI talks about numbers in this book, not the world.
- **Cross-book queries for front-office users.** Risk-manager cross-desk views require ACL hardening — defer.
- **Persistent multi-session memory.** Each conversation starts clean. Saved queries cover the "remember my favourite question" use case.
- **Per-user Claude billing / OAuth.** Single host subscription only in v2.
- **Verification-hedge copy.** No "verify with your team", no "this is not financial advice" boilerplate. Citation is the trust anchor.
- **Persistent chat pane / floating bubble / toast spam.** Inline-explainer + notification strip + ⌘K only.
- **Recommendations UI.** Existing `HedgeRecommendationPanel` stays its own surface — do not conflate with copilot.
- **Data-infra items beyond the two approved tables.** Cross-book materialised views, fx_rate_history, prices partitioning, settlement_date column, instruments.attributes enforcement — all deferred.
- **Grafana copilot-analytics dashboard.** Wire metrics in v2; build the dashboard post-v2.
- **Per-user Claude session isolation.** Single host credential means we audit per-user in our logs but rely on the SDK's stateless `query()` for cross-user isolation.

---

## Execution plan

### PR 0 — Baseline gate

Verify v1 AI features and existing infra are green before any v2 work lands.

- [x] 0.1 Confirm v1 baseline is green — run existing AI test suite across all modules touched by v2 (`ai-insights-service` unit, `gateway` `InsightsRoutesAcceptanceTest`, UI `AIInsightPanel` + `VaRDashboard` Vitest, Playwright `var-explainer.spec.ts` and `report-commentary.spec.ts`). No new code; this is the gate.
      Acceptance: `cd ai-insights-service && uv run pytest -m unit && cd .. && ./gradlew :gateway:acceptanceTest --tests "*InsightsRoutesAcceptanceTest" && cd ui && npm run test -- AIInsightPanel VaRDashboard && npx playwright test e2e/var-explainer.spec.ts e2e/report-commentary.spec.ts`

### PR 1 — Foundation: ADR + dependencies + MCP scaffold

- [x] 1.1 Draft `docs/adr/ADR-0036-ai-copilot-architecture.md`: covers in-process MCP, service-principal auth, SSE for chat, WebSocket for push, conversation state model, demo-mode strategy, and `~/.claude/` credential multi-tenancy limitations. Update `docs/adr/README.md` index.
      Acceptance: `test -f docs/adr/ADR-0036-ai-copilot-architecture.md && grep -q 'ADR-0036' docs/adr/README.md`
- [x] 1.2 Add Python deps to `ai-insights-service/pyproject.toml`: `mcp`, `aiokafka`, `redis[hiredis]`, `prometheus-client`. Run `uv sync`. No code changes; deps only.
      Acceptance: `cd ai-insights-service && uv sync && uv run python -c "import mcp, aiokafka, redis, prometheus_client"`
- [x] 1.3 Add `KinetixHttpClient` abstraction in `src/kinetix_insights/clients/kinetix_http_client.py` — async httpx wrapper that stamps `X-User-Id` and `X-User-Books` headers from a `UserContext` dataclass on every call. Inject base URLs via env (`POSITION_SERVICE_URL`, `RISK_ORCHESTRATOR_URL`, `PRICE_SERVICE_URL`, etc.). Fake implementation in `tests/fakes/fake_kinetix_http_client.py` for unit tests.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_kinetix_http_client.py -m unit`
- [x] 1.4 Add `Citation` pydantic model in `src/kinetix_insights/citations/models.py` with full schema (`tool`, `params`, `result_field`, `result_value`, `result_currency`, `as_of_timestamp`, `data_source`, `freshness_seconds`, `quality_flags`). Unit-test JSON round-trip and required-field validation.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_citation_models.py -m unit`
- [x] 1.5 Add `citation_verifier` in `src/kinetix_insights/citations/verifier.py`: tokenises narrative with `\$?[\d,]+(?:\.\d+)?%?` (handles JPY no-decimal and BHD three-decimal), checks each numeric token against `citation.result_value` matches. Returns list of uncited tokens. Pure utility; unit-tested in isolation.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_citation_verifier.py -m unit`
- [x] 1.6 Add `policy_guard` in `src/kinetix_insights/policy/banned_phrases.py`: compiled regex matching banned phrases (`you should`, `i recommend`, `consider hedging`, `consider reducing`, `you might want to`, `my advice`, `i suggest`, `you ought to`, `verify with your team`, `please confirm with`). Returns `POLICY_VIOLATION` on match. Unit tests cover positive matches and clean narratives.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_policy_guard.py -m unit`
- [x] 1.7 Add MCP server scaffold in `src/kinetix_insights/mcp/server.py`: registers a FastMCP instance, mounts on internal port 8096 in `app.py` lifespan, exposes a `/mcp/health` route. No tools yet — scaffold only.
      Acceptance: `cd ai-insights-service && DEMO_MODE=true uv run pytest tests/test_mcp_server_scaffold.py -m unit`

### PR 2 — MCP read tools (one tool per checkbox)

Each tool lives in its own file under `src/kinetix_insights/mcp/tools/`. Each carries a tool-level freshness SLA used to compute `freshness_seconds` in the citation. Each unit test stubs `KinetixHttpClient` and asserts: correct URL/params built, citation populated, error-code propagation (`NOT_FOUND`, `UPSTREAM_ERROR`).

- [x] 2.1 `get_book_var(book_id, as_of?, method?)` — reads risk-orchestrator latest `valuation_jobs` or by jobId; returns `{total_var, var_by_asset_class[], confidence_level, lookback_days, citation}`. Single-book scope, fails closed on book not in user's `X-User-Books`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_book_var.py -m unit`
- [x] 2.2 `get_positions(book_id, instrument_id?, asset_class?, top_n?)` — reads position-service `positions`; returns positions with delta, mtm, pnl_today; flags positions where `updatedAt` is stale relative to latest price timestamp.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_positions.py -m unit`
- [x] 2.3 `get_greeks_summary(book_id, as_of?, underlier?)` — reads risk-orchestrator `sod_greek_snapshots` + latest `valuation_jobs` Greeks; returns aggregate + by-underlier.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_greeks_summary.py -m unit`
- [x] 2.4 `get_limit_utilisation(book_id, limit_type?)` — reads position-service limit state; returns `{limits[{name, current, limit, utilisation_pct, status: GREEN|AMBER|RED}]}`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_limit_utilisation.py -m unit`
- [x] 2.5 `get_pnl_attribution(book_id, date?, period?)` — reads risk-orchestrator `pnl_attributions` + `intraday_pnl_snapshots` for sub-daily; surfaces `dataQualityFlag` in citation `quality_flags`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_pnl_attribution.py -m unit`
- [x] 2.6 `get_vol_surface(underlier, as_of?)` — reads volatility-service; detects inversions (short-dated ATM > long-dated by >2 vol points); returns surface + inversion flags.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_vol_surface.py -m unit`
- [x] 2.7 `get_stress_scenarios(book_id, scenarios?)` — reads risk-orchestrator precomputed named-scenario cache (GFC, EUR-crisis, Fed+25bps); returns `{scenarios[{name, pnl_impact, var_impact, key_driver}]}`. Ad-hoc scenarios out of scope for v2.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_stress_scenarios.py -m unit`
- [x] 2.8 `get_correlation_matrix(asset_pair?, as_of?, lookback_days?)` — reads correlation-service; flags pairs that moved >0.15 from prior day (correlation breaks).
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_correlation_matrix.py -m unit`
- [x] 2.9 `get_active_alerts(book_id, severity?, since?)` — reads notification-service active alerts; single-book unless caller has risk-manager role (out of scope v2; defaults to single-book).
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_active_alerts.py -m unit`
- [x] 2.10 `get_market_data_snapshot(instruments, fields?)` — reads price-service; returns quotes with change_pct, change_abs, as_of. Resolves counterparty/issuer names via reference-data fuzzy-match utility (`resolve_counterparty`); returns "not found" rather than guessing.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_market_data_snapshot.py -m unit`
- [x] 2.11 Wire all 10 tools into the MCP server registry in `src/kinetix_insights/mcp/server.py`; assert the SDK can discover each by name. Integration test stands up an in-process MCP client and lists/calls each tool against a fake `KinetixHttpClient`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_mcp_tool_registry.py -m unit`

### PR 3 — Chat endpoint: streaming SSE, citation contract, policy guards

- [x] 3.1 Add `ChatRequest` and `ChatChunk` pydantic models in `src/kinetix_insights/chat/models.py`. `ChatRequest`: `{message, page_context, session_id?, conversation_id?}`. `ChatChunk`: `{delta?, done, citations?, model?, mode?, error_code?}`. Unit-test serialisation.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_chat_models.py -m unit`
- [ ] 3.2 Add `_FakeStreamingSdk` shared test fake in `tests/fakes/streaming_sdk.py` — yields multi-message responses with configurable per-message delays and content; usable across chat, brief, and queries tests.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_streaming_sdk_fake.py -m unit`
- [ ] 3.3 Add `ConversationStore` protocol in `src/kinetix_insights/chat/conversation_store.py` + `InMemoryConversationStore` impl. TTL 24h via `OrderedDict` + timestamp eviction. Unit-tested for add/get/expire.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_conversation_store_in_memory.py -m unit`
- [ ] 3.4 Add `CopilotChatClient` protocol + `CannedCopilotChatClient` in `src/kinetix_insights/chat/canned.py`. Replays a multi-turn fixture from `src/kinetix_insights/fixtures/chat_transcripts/*.json` as SSE chunks with 20 ms artificial delay. Selects transcript by hash of `(message + page_context.page)`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_canned_chat_client.py -m unit`
- [ ] 3.5 Add `ClaudeAgentCopilotChatClient` in `src/kinetix_insights/chat/claude_agent_chat_client.py` — wraps SDK `query()` with MCP tools enabled, accumulates conversation history from `ConversationStore`, applies citation_verifier + policy_guard before yielding final chunk. Uses `_FakeStreamingSdk` in unit tests; never calls live SDK.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_claude_agent_chat_client.py -m unit`
- [ ] 3.6 Add `POST /api/v1/insights/chat` SSE route in `src/kinetix_insights/routes/chat.py`. Streams `data: {ChatChunk}\n\n` lines; emits `event: source` frames for citations; final frame `done:true` with `session_id`, `conversation_id`, `model`, `mode`. Acceptance test via `httpx.AsyncClient(stream=True)` against `TestClient` with `DEMO_MODE=true`.
      Acceptance: `cd ai-insights-service && DEMO_MODE=true uv run pytest tests/test_chat_acceptance.py -m unit`
- [ ] 3.7 Add negative-test suite `tests/test_chat_guardrails.py`: asserts (a) banned-phrase narrative returns `POLICY_VIOLATION`, (b) uncited numeric token returns `CITATION_UNVERIFIABLE`, (c) hallucinated ticker (not in tool results) is flagged, (d) cross-user prompt-injection in trade comments is sanitised, (e) tool timeout produces a citation entry with `status: timeout` rather than hanging the stream.
      Acceptance: `cd ai-insights-service && DEMO_MODE=true uv run pytest tests/test_chat_guardrails.py -m unit`
- [ ] 3.8 Add chat latency budget test in `tests/test_chat_latency.py -m performance`: with a `_FakeStreamingSdk` yielding first chunk at 0 ms, assert service overhead from request submit to first SSE chunk is <500 ms.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_chat_latency.py -m performance`

### PR 4 — Gateway streaming proxy + chat route

- [ ] 4.1 Add `streamProxyToInsights` in `gateway/src/main/kotlin/com/kinetix/gateway/routes/InsightsRoutes.kt` — uses `HttpStatement.execute {}` + `respondBytesWriter` to pipe SSE byte-for-byte. Existing `proxyToInsights` stays untouched for the two v1 explainers.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*InsightsStreamingProxyAcceptanceTest"`
- [ ] 4.2 Register `POST /api/v1/insights/chat` route in `gateway/.../routes/InsightsRoutes.kt` using the new streaming proxy. Update HTTP client timeout for this route family to `Long.MAX_VALUE` (or large bound). Acceptance verifies SSE content-type and at least one chunk passes through.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*CopilotChatRouteAcceptanceTest"`
- [ ] 4.3 Add JWT → header bridging in the proxy: extract `sub` claim, write to `X-User-Id`; extract `books` claim (or equivalent ACL), write to `X-User-Books` comma-list. Unit-tested against fixture JWTs.
      Acceptance: `./gradlew :gateway:test --tests "*JwtToHeaderBridgeTest"`

### PR 5 — UI chat surface: ⌘K + StreamingNarrative + citations

- [ ] 5.1 Add `ExplainPayload` discriminated-union type + new `ui/src/api/copilot.ts` with `chat()` returning a `ReadableStream<ChatChunk>` (uses `EventSource` + reader pump). Vitest covers happy-path stream consumption + abort behaviour.
      Acceptance: `cd ui && npm run test -- copilot.test`
- [ ] 5.2 Add `<StreamingNarrative>` component in `ui/src/components/StreamingNarrative.tsx`: accepts a `ReadableStream<ChatChunk>` prop, owns token accumulation via `useRef` + `requestAnimationFrame` batching (50 ms), renders skeleton → blinking-cursor → token-flow → complete. Reduced-motion fallback. Vitest covers all four states.
      Acceptance: `cd ui && npm run test -- StreamingNarrative`
- [ ] 5.3 Add `<CitationFootnote>` and `<CitationList>` in `ui/src/components/CitationFootnote.tsx` and `CitationList.tsx`. Inline superscript + footer list with `<details>` for params. Vitest covers: numeric token wrapped in `<cite>`, uncited token shows `[uncited]` marker, citations rendered in tool-call order.
      Acceptance: `cd ui && npm run test -- Citation`
- [ ] 5.4 Add `<ExplainButton>` in `ui/src/components/ExplainButton.tsx` with `Sparkles` icon. Refactor `VaRDashboard.tsx`'s inline button to use it (regression: existing Playwright `var-explainer.spec.ts` must still pass).
      Acceptance: `cd ui && npm run test -- ExplainButton && cd ui && npx playwright test e2e/var-explainer.spec.ts`
- [ ] 5.5 Refactor `<AIInsightPanel>` to compose `<StreamingNarrative>` when a stream prop is provided; keep the existing canned/non-streaming path for `ReportsTab` regression. Vitest + existing Playwright must still pass.
      Acceptance: `cd ui && npm run test -- AIInsightPanel && cd ui && npx playwright test e2e/report-commentary.spec.ts`
- [ ] 5.6 Extend `<CommandPalette>` with `copilotMode?: boolean` prop + a streaming-response zone below the input. When ⌘K is opened and the user types free-form, fire `chat()` and render `<StreamingNarrative>` + `<CitationList>`. Add multi-turn follow-up textarea (Shift+Enter newline; Enter sends). Vitest covers the new zone.
      Acceptance: `cd ui && npm run test -- CommandPalette`
- [ ] 5.7 Add `page_context` plumbing: a `useCopilotContext()` hook that reads route + visible selections (book, scenario, VaR result) and serialises them. Used by both `<ExplainButton>` and `<CommandPalette>`. Vitest covers context shape per route.
      Acceptance: `cd ui && npm run test -- useCopilotContext`
- [ ] 5.8 Add `chatMockCanned` helper to `ui/e2e/fixtures.ts` — fulfils `/api/v1/insights/chat` with a multi-chunk SSE body. Add Playwright `ui/e2e/copilot-chat.spec.ts` covering: ⌘K open, free-form question, streaming token render, citation chip click, Esc-cancels-stream, demo-mode badge visible.
      Acceptance: `cd ui && npm run lint && npx playwright test e2e/copilot-chat.spec.ts`

### PR 6 — Morning brief: pipeline + UI

- [ ] 6.1 Flyway migration `position-service/.../V???__create_limit_breach_events.sql` creating `limit_breach_events(id, entity_id, book_id, limit_type, severity, current_value, limit_value, breached_at, resolved_at)` with indexes on `(book_id, breached_at DESC)` and `(severity, breached_at DESC)`. Acceptance: position-service migration tests pass.
      Acceptance: `./gradlew :position-service:test --tests "*LimitBreachEventsMigrationTest"`
- [ ] 6.2 Add `LimitBreachEventWriter` hook in position-service `LimitHierarchyService` — persists every detected breach (and resolution) to `limit_breach_events`. Acceptance tests assert breach write + resolution write + idempotency.
      Acceptance: `./gradlew :position-service:acceptanceTest --tests "*LimitBreachEventWriterAcceptanceTest"`
- [ ] 6.3 Add MCP tool `get_recent_breaches(book_id, since?)` in `ai-insights-service/.../mcp/tools/` reading from `limit_breach_events` via position-service HTTP API. Unit test against `FakeKinetixHttpClient`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_get_recent_breaches.py -m unit`
- [ ] 6.4 Add MCP tool `search_audit_log(query, since?, until?, book_id?)` reading audit-service `audit_events` with mandatory time-range. Unit test asserts time-range default of 7 days when omitted.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_tool_search_audit_log.py -m unit`
- [ ] 6.5 Add `MorningBriefGenerator` in `src/kinetix_insights/brief/generator.py`: per-book iteration over `get_book_var`, `get_pnl_attribution`, `get_recent_breaches`, `get_limit_utilisation`, `get_greeks_summary` deltas vs SOD; assembles `MorningBrief{sections[{title, narrative, bullets, sources, severity}], generated_at, mode}`. Per-book errors do NOT abort the batch — surface as `status: timeout/error` sections.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_morning_brief_generator.py -m unit`
- [ ] 6.6 Add `CannedBriefClient` in `src/kinetix_insights/brief/canned.py` returning a fixture from `fixtures/demo_brief.json`. Add `ClaudeAgentBriefClient` wrapping the generator + SDK summarisation. Factory selects via `DEMO_MODE`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_brief_factory.py -m unit`
- [ ] 6.7 Add `GET /api/v1/insights/brief/today` route — returns 200 with brief if generated, 202 with `{status: generating, retry_after}` otherwise. Background lifespan task schedules generation at 06:30 local (cron via `asyncio` sleep loop) and on-demand if not yet generated when first requested today.
      Acceptance: `cd ai-insights-service && DEMO_MODE=true uv run pytest tests/test_brief_acceptance.py -m unit`
- [ ] 6.8 Add gateway proxy route for `/api/v1/insights/brief/today` (non-streaming; reuse existing `proxyToInsights`). Acceptance test asserts pass-through with `mode` field preserved.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*CopilotBriefRouteAcceptanceTest"`
- [ ] 6.9 Add `<NotificationStrip>` + `<NotificationInbox>` in `ui/src/components/NotificationStrip.tsx`. 36px collapsed bar below `<SystemStatusBanner>`, above `<RiskTickerStrip>`. Severity chips, "N unread", expand-to-inbox (max-height 320px scroll). Per-item dismiss + dismiss-all; dismissed IDs in `localStorage` keyed `kinetix:copilot-inbox:dismissed`. Vitest covers collapsed/expanded/empty/error states.
      Acceptance: `cd ui && npm run test -- NotificationStrip`
- [ ] 6.10 Add `<MorningBriefCard>` in `ui/src/components/MorningBriefCard.tsx` rendered inside `<NotificationInbox>`. On first inbox open of the trading day (compare `localStorage` key `kinetix:morning-brief:last-seen-date` vs today), auto-expand inbox + scroll to brief items. Add Playwright `ui/e2e/morning-brief.spec.ts` covering brief render, auto-expand-on-first-load, dismiss-without-losing-access, demo-mode badge.
      Acceptance: `cd ui && npm run lint && npm run test -- MorningBriefCard && npx playwright test e2e/morning-brief.spec.ts`

### PR 7 — Intraday push: Kafka consumer + WebSocket + UI

- [ ] 7.1 Flyway migration `risk-orchestrator/.../V???__create_copilot_alert_thresholds.sql` creating `copilot_alert_thresholds(id, scope_type ENUM(GLOBAL,BOOK,USER), scope_id NULL, alert_type, threshold_value, cooldown_minutes)` with composite index `(scope_type, scope_id, alert_type)`. Seed 10 global defaults (VaR 5%, position delta $500K, vol inversion 2vp, gamma 15%, limit 80%, counterparty $10M, price 3σ, unexplained P&L $100K, regime change, diversification 20%).
      Acceptance: `./gradlew :risk-orchestrator:test --tests "*CopilotAlertThresholdsMigrationTest"`
- [ ] 7.2 Add `IntradayThresholdEvaluator` in `src/kinetix_insights/push/threshold_evaluator.py`: reads thresholds via new MCP tool `get_alert_thresholds(scope)`; evaluates a `RiskResult` Kafka event against applicable thresholds; emits `(alert_type, severity, book_id, current, threshold, cooldown_key)` or None. Cooldown dedupe via in-memory `cachetools.TTLCache`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_threshold_evaluator.py -m unit`
- [ ] 7.3 Add `aiokafka` consumer in `src/kinetix_insights/push/kafka_consumer.py` on topics `risk.results` and `risk.regime.changes` with group `ai-insights-risk-consumer`. Each message passes through `IntradayThresholdEvaluator`; firing alerts call into an `IntradayPushGenerator`. Lifespan starts/stops the consumer.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_intraday_kafka_consumer.py -m unit`
- [ ] 7.4 Add `IntradayPushGenerator` — composes a `{alert_type, severity, book_id, headline, context_bullets, sources, session_id, generated_at}` payload; sources include the original tool calls used to evaluate the threshold. Canned variant for `DEMO_MODE`. Unit tests for both modes.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_intraday_push_generator.py -m unit`
- [ ] 7.5 Add gateway internal route `POST /internal/copilot/push` in `gateway/.../routes/CopilotInternalRoutes.kt` — accepts the push payload from `ai-insights-service` (cluster-internal only; no auth challenge). Enqueues to `CopilotBroadcaster`. Test asserts external requests are rejected and internal requests flow through.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*CopilotInternalPushAcceptanceTest"`
- [ ] 7.6 Add `CopilotBroadcaster` in `gateway/.../websocket/CopilotBroadcaster.kt` (mirrors `AlertBroadcaster`) and `/ws/copilot` WebSocket route in `CopilotWebSocketRoute.kt`. JWT auth on connect; scope-filter by `X-User-Books`. Register in `Application.kt` `devModule()`. Test asserts message delivery to scoped users only.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*CopilotWebSocketRouteAcceptanceTest"`
- [ ] 7.7 Wire `ai-insights-service` to POST to the gateway internal endpoint when an intraday alert fires. Add `GatewayPushClient` (httpx wrapper). Unit test asserts the push payload shape and URL.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_gateway_push_client.py -m unit`
- [ ] 7.8 Add `useCopilotWebSocket()` hook in `ui/src/hooks/useCopilotWebSocket.ts` — connects to `/ws/copilot`, exposes push events as a React state stream. Auto-reconnect with backoff. Vitest covers connect/disconnect/reconnect/scope-filter.
      Acceptance: `cd ui && npm run test -- useCopilotWebSocket`
- [ ] 7.9 Render intraday push events as items in `<NotificationStrip>` with `Zap` icon and time-of-trigger label. Overflow >5 collapses to "N more" badge. Vitest covers overflow + severity color contract.
      Acceptance: `cd ui && npm run test -- NotificationStripIntraday`
- [ ] 7.10 Add Playwright `ui/e2e/intraday-push.spec.ts` — mock the WebSocket to emit a pre-scripted push payload; assert strip appears with narrative + sources + dismiss. Also assert 50-payload overflow collapses to "45 more".
      Acceptance: `cd ui && npm run lint && npx playwright test e2e/intraday-push.spec.ts`

### PR 8 — Saved queries

- [ ] 8.1 Add 5 built-in saved-query templates as JSON files under `src/kinetix_insights/queries/`: `limit-breaches.json`, `pnl-vs-yesterday.json`, `var-week-drivers.json`, `top-positions-risk-contribution.json`, `vol-dislocations.json`. Each carries `{id, label, prompt_template, required_params}`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_query_templates_load.py -m unit`
- [ ] 8.2 Add `POST /api/v1/insights/queries/{id}/run` route — loads template by id, interpolates params from request, reuses `CopilotChatClient` for execution. Returns SSE in the same shape as `/chat`. Acceptance against `DEMO_MODE=true`.
      Acceptance: `cd ai-insights-service && DEMO_MODE=true uv run pytest tests/test_saved_queries_acceptance.py -m unit`
- [ ] 8.3 Add `<SavedQueryChip>` component in `ui/src/components/SavedQueryChip.tsx` (pill UI per UX spec); render the 5 defaults with Lock icon at top of inbox + as a "Copilot" group in ⌘K's empty-query state. `localStorage`-backed user queries (max 12) with save-from-palette flow. Vitest + Playwright.
      Acceptance: `cd ui && npm run test -- SavedQueryChip && cd ui && npm run lint && npx playwright test e2e/saved-queries.spec.ts`

### PR 9 — Inline explainer rollout

- [ ] 9.1 Add `<ExplainButton>` to `PositionRiskTable` rows (rightmost 32 px action column, label on row focus/selection) + table header (portfolio-level explain). Each click opens `<AIInsightPanel>` with `<StreamingNarrative>` consuming `/chat` with the row's position payload as `page_context`. Playwright covers per-row open, double-click protection, "only one panel open" behaviour.
      Acceptance: `cd ui && npm run lint && npx playwright test e2e/inline-explainer-positions.spec.ts`
- [ ] 9.2 Add `<ExplainButton>` to the P&L attribution chart container (above the waterfall). Payload includes top-N drivers + date. Playwright covers the explain flow.
      Acceptance: `cd ui && npm run lint && npx playwright test e2e/inline-explainer-pnl.spec.ts`
- [ ] 9.3 Add `<ExplainButton>` to each row in the alerts/breaches panel (`NotificationCenter`). Payload includes alertId, type, currentValue, threshold, severity. Playwright covers alert-row explain.
      Acceptance: `cd ui && npm run lint && npx playwright test e2e/inline-explainer-alerts.spec.ts`
- [ ] 9.4 Add `<ExplainButton>` to scenario result rows in `StressTestPanel`. Payload includes scenario name, stressed PnL, top stressed positions. Playwright covers scenario-row explain.
      Acceptance: `cd ui && npm run lint && npx playwright test e2e/inline-explainer-scenarios.spec.ts`
- [ ] 9.5 Add `<ExplainButton>` to `GreeksPanel` (aggregate Greeks card) and `CorrelationMatrix` (matrix-level explain for correlation breaks). Playwright covers both.
      Acceptance: `cd ui && npm run lint && npx playwright test e2e/inline-explainer-greeks-correlation.spec.ts`

### PR 10 — Hardening: Redis state, rate limit, audit, telemetry, isolation

- [ ] 10.1 Add `RedisConversationStore` impl of `ConversationStore` protocol; selected when `REDIS_URL` env is set. Testcontainers Redis integration test asserts add/get/expire after 24h TTL.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_conversation_store_redis_integration.py -m integration`
- [ ] 10.2 Add Ktor `rateLimit` plugin to gateway routes `/api/v1/insights/chat` and `/api/v1/insights/queries/*/run` — 10 req / user / minute keyed by JWT `sub`. 429 on breach. Acceptance test asserts 429 behaviour.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*CopilotRateLimitAcceptanceTest"`
- [ ] 10.3 Add structured audit logging in `ai-insights-service`: every chat / brief / query / push call writes one structured log line with `{user_id, endpoint, prompt_hash, tool_calls[], tokens_estimated, mode, latency_ms, timestamp}` via Python `logging` + JSON formatter (Loki-compatible). Acceptance asserts log line format.
      Acceptance: `cd ai-insights-service && DEMO_MODE=true uv run pytest tests/test_audit_logging.py -m unit`
- [ ] 10.4 Add Prometheus metrics namespace `copilot_*` per the data-analyst spec (histograms: `copilot_tool_call_duration_seconds`, `copilot_first_byte_latency_seconds`, `copilot_brief_generation_duration_seconds`; counters: `copilot_chat_session_total`, `copilot_tool_not_found_total`, `copilot_citation_empty_result_total`, `copilot_policy_violation_total`, `copilot_freshness_sla_breach_total`, `copilot_sdk_error_total`, `copilot_demo_mode_fallback_total`, `copilot_redis_cache_hit_total`). Expose at `/metrics`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_prometheus_metrics.py -m unit`
- [ ] 10.5 Add multi-user isolation acceptance test: two stub backends (user A's books, user B's books) on random ports; two concurrent `/chat` requests with different forwarded `X-User-Id`s; assert each session only reaches its own stub and that neither response contains the other user's sentinel string.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_chat_user_isolation_integration.py -m integration`
- [ ] 10.6 Add UI Playwright latency budget test `ui/e2e/copilot-latency.spec.ts` — `waitForFunction` asserting first non-empty `chat-stream-content` within 3000 ms of submit, with a mocked endpoint that emits first chunk at 50 ms.
      Acceptance: `cd ui && npm run lint && npx playwright test e2e/copilot-latency.spec.ts`
- [ ] 10.7 Add `ProductionHardeningAcceptanceTest` assertion in the gateway test suite: confirms `DEMO_MODE=true` is the configured value for the `ai-insights-service` container in the test profile, and that `grep -r "verify with your team" ai-insights-service/src/` returns nothing (banned-phrase scrub of canned fallbacks).
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*ProductionHardeningAcceptanceTest" && ! grep -r "verify with your team" ai-insights-service/src/`

### PR 11 — Wrap-up: demo seed + README pointer

- [ ] 11.1 Add `/demo` orchestrator hook that pre-stages a deterministic morning brief, one queued intraday push, and a sample saved-query result so the 90-second demo script runs end-to-end without any live SDK call. Doc the demo script in `ai-insights-service/README.md` under a new "v2 demo flow" section.
      Acceptance: `grep -q "v2 demo flow" ai-insights-service/README.md && cd ui && npx playwright test e2e/copilot-demo-walkthrough.spec.ts`
- [ ] 11.2 Update root `README.md` "Built with Claude Code" hero with a one-line pointer to v2 features ("Kinetix Copilot — morning brief, intraday push, ⌘K") and a link to ADR-0036 + this plan. Refresh `docs/evolution-report.md` final paragraph with the v2 completion date.
      Acceptance: `grep -q "Kinetix Copilot" README.md && grep -q "ADR-0036" README.md && grep -q "ai-v2" README.md`
