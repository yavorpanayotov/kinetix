# AI Features

Kinetix integrates LLM-powered explainers and (in v2) a read-only Copilot. Everything runs through a dedicated Python service — `ai-insights-service` — that wraps the [Claude Agent SDK](https://docs.anthropic.com/en/docs/claude-code/sdk) and reuses the host's `~/.claude/` Claude Code subscription. There is no `ANTHROPIC_API_KEY` anywhere in the platform; there is no per-token billing.

This page is the single reference for the AI integration. The authoritative architecture decision is [ADR-0036](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0036-ai-copilot-architecture.md); the v1 and v2 execution plans live in [`docs/plans/ai-v1.md`](https://github.com/panayotovk/kinetix/blob/main/docs/plans/ai-v1.md) and [`docs/plans/ai-v2.md`](https://github.com/panayotovk/kinetix/blob/main/docs/plans/ai-v2.md).

## Status

| Phase | Surface | Status |
|---|---|---|
| v1 | VaR Explainer on the Risk tab | Shipped |
| v1 | AI Commentary on the Reports tab | Shipped |
| v1 | `ai-insights-service` scaffold, factory, LRU cache, canned client | Shipped |
| v2 | ADR-0036 Copilot architecture | Accepted |
| v2 | MCP server scaffold + 10 read tools | Shipped |
| v2 | `Citation` model + `citation_verifier` | Shipped |
| v2 | `policy_guard` banned-phrase filter | Shipped |
| v2 | `KinetixHttpClient` + service-principal headers | Shipped |
| v2 | `ChatRequest` / `ChatChunk` models | Shipped |
| v2 | Streaming chat endpoint (SSE) | Shipped |
| v2 | Morning brief (scheduled + on-demand) | Shipped |
| v2 | Intraday push (Kafka consumer + WebSocket) | Shipped |
| v2 | ⌘K command palette with saved-query chips | Shipped |
| v2 | Demo polish: launcher, freshness urgency, tool_calls reasoning, book-reset, friendly errors, source-of-truth footnote | In flight (`docs/plans/ai-copilot-demo-polish.md`) |

## v1 — shipped explainers

Two user-visible features running off the same response contract.

### VaR Explainer

- **Where:** Risk tab, beside the VaR gauge — an **Explain** button.
- **What:** the model receives `{method, confidence, horizon_days, value_usd, top_contributors[{instrument, contribution_pct}], regime}` and produces a short narrative + bulleted contributors.
- **Endpoint:** `POST /api/v1/insights/explain/var` on the gateway, proxied to `ai-insights-service`.

### Report Commentary

- **Where:** Reports tab — every generated report renders an AI Commentary card below it.
- **What:** drivers, limit breaches, and regulatory-flavour narrative for the report payload.
- **Endpoint:** `POST /api/v1/insights/explain/report`.

### Shared response shape

Both endpoints return the same DTO so the UI reuses one component (`AIInsightPanel`):

```json
{
  "narrative": "…",
  "bullets": ["…", "…"],
  "model": "claude-sonnet-4-6",
  "mode": "live"   // or "canned"
}
```

The `mode` field drives a "Demo mode" badge in the UI whenever the canned client served the response — see [Demo mode and host auth](#demo-mode-and-host-auth) below.

## v2 — Kinetix Copilot foundation

The v2 target is a **proactive** copilot — morning brief, intraday push, inline explainers, and a ⌘K free-form fallback — backed by a Claude Code SDK conversation grounded in Kinetix's own data. The trader review by Marcus (senior FX/rates desk) drove the verdict: *"Kinetix tells you what changed and why, before you ask."* Chat is the escape hatch, not the headline.

**Write actions are explicitly out of scope.** No "execute this hedge", no "submit this order", no "adjust this limit", no trade-booking. Hedging recommendations and macro views are also out. The Copilot reads, narrates, and cites; it does not advise.

### In-process MCP server

`ai-insights-service` mounts a [Model Context Protocol](https://modelcontextprotocol.io/) server on internal port 8096. The Claude Agent SDK invokes registered tools over the local transport; tool implementations call downstream Kinetix services over HTTP via `KinetixHttpClient`. The MCP port is **not exposed via the gateway** — it is cluster-internal only.

### Read tools (v2 PR 2 — shipped)

| Tool | Reads | Purpose |
|---|---|---|
| `get_book_var` | risk-orchestrator `valuation_jobs` | Total VaR, VaR by asset class, confidence, lookback |
| `get_positions` | position-service `positions` | Delta, MTM, P&L today; flags stale rows |
| `get_greeks_summary` | risk-orchestrator `sod_greek_snapshots` + latest `valuation_jobs` | Aggregate + by-underlier Greeks |
| `get_limit_utilisation` | position-service limit state | Limit utilisation with GREEN/AMBER/RED status |
| `get_pnl_attribution` | risk-orchestrator `pnl_attributions` + `intraday_pnl_snapshots` | Greek decomposition; surfaces dataQualityFlag |
| `get_vol_surface` | volatility-service | Surface + flags for short-vs-long inversions (>2 vol points) |
| `get_stress_scenarios` | risk-orchestrator named-scenario cache | Precomputed (GFC, EUR-crisis, Fed+25bps); ad-hoc out of scope for v2 |
| `get_correlation_matrix` | correlation-service | Matrix + flags for pairs that moved >0.15 vs prior day |
| `get_active_alerts` | notification-service | Active alerts, single-book scope |
| `get_market_data_snapshot` | price-service + reference-data fuzzy-match | Quotes with change_pct, change_abs, as_of |

Each tool is single-book scoped via `X-User-Books` and fails closed when the requested book is outside the caller's scope. Every tool result carries a freshness SLA used to compute `freshness_seconds` in the citation.

### Citation contract

Every numeric token in any AI response carries a machine-readable `Citation`:

```json
{
  "tool": "get_book_var",
  "params": {"book_id": "EQ-001"},
  "result_field": "total_var",
  "result_value": "12450000",
  "result_currency": "USD",
  "as_of_timestamp": "2026-05-19T13:45:00Z",
  "data_source": "risk-orchestrator",
  "freshness_seconds": 42,
  "quality_flags": []
}
```

The model is prompt-instructed never to state a number without a citation. A post-generation `citation_verifier` tokenises the narrative with `\$?[\d,]+(?:\.\d+)?%?` (handles JPY no-decimal and BHD three-decimal), checks each token against the citation's `result_value`, and flags mismatches as `CITATION_UNVERIFIABLE`. Citation is the trust anchor — there is **no** "verify with your team" hedge copy.

### Policy guard

A compiled regex matches banned phrases — `you should`, `i recommend`, `consider hedging`, `consider reducing`, `you might want to`, `my advice`, `i suggest`, `you ought to`, `verify with your team`, `please confirm with` — and returns `POLICY_VIOLATION` on match. Enforced server-side; the model isn't trusted to follow the contract on its own.

### Service-principal auth

The single host `~/.claude/` credential does not provide per-user isolation. To preserve user scopes the gateway extracts the user's JWT `sub` claim and forwards two headers on every downstream call:

- `X-User-Id` — the principal making the request
- `X-User-Books` — comma-separated list of book IDs the user is scoped to

`KinetixHttpClient` stamps these headers on every call from MCP tools, and the same `user_id` is written into every audit log entry. Per-user Claude billing / OAuth is out of scope for v2 (single host subscription, rate-limited at the gateway).

### Streaming and conversation state

- **Streaming transport:** Server-Sent Events (SSE) for chat, saved-query runs, and the morning brief.
- **Push transport:** WebSocket `/ws/copilot` on the gateway for intraday push.
- **Conversation state:** `ConversationStore` protocol with `InMemoryConversationStore` in v2 and a `RedisConversationStore` impl as the hardening item. TTL 24h.

(All of these surfaces are shipped. See `docs/plans/ai-v2.md` for the completed execution history and `docs/plans/ai-copilot-demo-polish.md` for ongoing demo-polish work.)

## Demo mode and host auth

The Agent SDK reads credentials from `~/.claude/` on the host. Three deployment contexts:

### Native local dev

```bash
claude --version   # must succeed
cd ai-insights-service && uv run uvicorn kinetix_insights.app:app --port 8095
```

No env vars required.

### Docker / docker compose

The container bind-mounts the host's Claude config read-only:

```yaml
services:
  ai-insights-service:
    image: kinetix/ai-insights-service:latest
    volumes:
      - ~/.claude:/home/kinetix/.claude:ro
    ports:
      - "8095:8095"
```

Without the mount the SDK cannot reach an authenticated CLI and the service falls back to canned mode.

### CI / Playwright

`DEMO_MODE=true` is always set. CI never reaches the live SDK; a pytest fixture raises if `DEMO_MODE != "true"` in CI.

### Fallback behaviour

If `DEMO_MODE` is unset but the SDK can't reach an authenticated CLI (Docker without the mount, host without `claude`), `factory.build_client()` returns `CannedInsightClient` and responses carry `mode="canned"`. The UI's "Demo mode" badge surfaces this to operators so they never mistake canned output for live LLM output.

### Response shape difference

```json
// Live mode
{"narrative": "…", "bullets": ["…"], "model": "claude-sonnet-4-6", "mode": "live"}

// Canned mode (deterministic templates)
{"narrative": "…", "bullets": ["…"], "model": "canned", "mode": "canned"}
```

## Threat model (high-level)

- **Single host credential:** all chat traffic shares one `~/.claude/` subscription. User isolation comes from forwarded `X-User-Id` / `X-User-Books` headers and per-user audit logs, not from credential separation. Risk-manager cross-book queries are deferred (ACL hardening still pending).
- **Cluster-internal MCP:** the MCP server on port 8096 is bound to the internal network only. It is not exposed via the gateway and not reachable from outside the cluster.
- **Internal-only push endpoint:** `POST /internal/copilot/push` on the gateway accepts pushes from `ai-insights-service` only; external requests are rejected.
- **Banned-phrase scrub:** canned fallbacks are scrubbed for banned phrases at acceptance-test time so demo-mode responses don't violate the policy contract.
- **Cross-user prompt injection:** trade comments and other user-supplied free-text reaching the model are sanitised; uncited tokens (including hallucinated tickers) are flagged.

## Files and layout

```
ai-insights-service/
├── pyproject.toml                FastAPI, Pydantic, claude-agent-sdk, mcp,
│                                 aiokafka, redis[hiredis], prometheus-client
├── Dockerfile                    uv-based build, exposes 8095
├── README.md                     Host-auth + DEMO_MODE quickstart
└── src/kinetix_insights/
    ├── app.py                    FastAPI app; lifespan builds client, mounts MCP
    ├── factory.py                build_client(): live vs canned selection
    ├── models.py                 InsightRequest, InsightResponse
    ├── insights_client.py        InsightClient protocol
    ├── claude_agent_client.py    Live client (wraps claude-agent-sdk)
    ├── canned.py                 Deterministic templates (mode="canned")
    ├── cache.py                  LRU (size 256) keyed by request hash
    ├── prompts.py                Per-kind prompt renderers
    ├── routes/
    │   ├── var_explainer.py      POST /api/v1/insights/explain/var (v1)
    │   └── report_commentary.py  POST /api/v1/insights/explain/report (v1)
    ├── chat/models.py            ChatRequest, ChatChunk (v2)
    ├── citations/
    │   ├── models.py             Citation pydantic model
    │   └── verifier.py           citation_verifier
    ├── policy/banned_phrases.py  policy_guard
    ├── clients/
    │   ├── kinetix_http_client.py  Async httpx wrapper + X-User-* headers
    │   └── user_context.py
    └── mcp/
        ├── server.py             FastMCP registry + tool wiring
        ├── health.py             /mcp/health route
        └── tools/                10 MCP read tools (one per file)
```

Related files outside the service:

- `gateway/src/main/kotlin/com/kinetix/gateway/routes/InsightsRoutes.kt` — proxy routes for v1 explainers
- `ui/src/components/AIInsightPanel.tsx` — narrative + bullets + "Demo mode" badge
- `ui/src/components/VaRDashboard.tsx`, `ui/src/components/ReportsTab.tsx` — surfaces that wire `AIInsightPanel`
- `docs/adr/0036-ai-copilot-architecture.md` — authoritative ADR
- `docs/plans/ai-v1.md`, `docs/plans/ai-v2.md` — execution plans
