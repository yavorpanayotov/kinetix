# AI Insights v1: Showcase AI Proficiency in Kinetix

## Context

Kinetix is being used as a portfolio piece to showcase AI-assisted-development proficiency. The **process** side is already deep — 15 custom Claude Code agents, 32 skills, 24 Allium specs, 36 ADRs, 57 archived agent worktrees, ~2,090 commits over ~14 weeks, loop-ready `/work-plan` plans, a memory system, `/distill → /weed → /propagate` workflow — but invisible unless a reviewer digs in. The **product** side has zero LLM-powered features; risk surfaces (VaR, Greeks, factor risk, hedge recs, scenarios) are numeric-only.

This plan ships:

1. **Process surface polish**: a `Built with Claude Code` README hero, a `HOW_IT_WAS_BUILT.md` page, an ADR index, an Allium spec index, and a refreshed evolution report — so the AI story is visible the moment anyone lands on the repo.
2. **Two visible LLM-powered product features** that any reviewer running `/demo` will see within 30 seconds — **VaR Explainer** on the Risk Dashboard and **AI Commentary** on the Reports tab — both backed by a new Python `ai-insights-service` that routes LLM calls through the user's **Claude Code subscription** via the **Claude Agent SDK** (no per-token Anthropic API spend).

## Status

This plan is loop-ready for `/work-plan`. Each `- [ ]` checkbox below is one independently-committable change, ordered top-to-bottom by dependency, with an `Acceptance:` command on the line directly after it. Advance end-to-end with `/loop /work-plan plans/ai-v1.md`.

## Decisions applied

- **LLM provider routing** — Claude via the **Claude Agent SDK** (Python), using the host's already-authenticated `claude` CLI. No `ANTHROPIC_API_KEY`. Aligns with the "built with Claude Code" narrative and avoids per-token spend.
- **Service name + language** — `ai-insights-service`, **Python** (FastAPI), uv + pyproject, matching `risk-engine` tooling. Python is non-negotiable: the Agent SDK ships Python + TypeScript only.
- **Host-auth model** — local dev: native, uses `~/.claude/`. Docker compose: bind-mount `~/.claude:/root/.claude:ro` on the `ai-insights-service` container. CI / Playwright: always `DEMO_MODE=true`, never depend on host auth.
- **DEMO_MODE fallback** — every endpoint has a deterministic canned-response client, selected when `DEMO_MODE=true` *or* when the SDK can't reach an authenticated CLI. Playwright tests always hit canned. Public visitors of any future hosted demo always hit canned.
- **Response shape** — every insight endpoint returns `{narrative: str, bullets: list[str], model: str, mode: "live" | "canned"}`. Same shape lets the UI reuse one component.
- **TDD** — test + minimal implementation land in the **same commit** so every commit builds green (CLAUDE.md "Commit Practices"). The "failing test first" discipline is preserved in the commit narrative, not in separate commits.
- **Cache** — in-process LRU (size 256) keyed by request hash; no Redis, no DB.
- **Scope** — exactly two product features in v1 (VaR Explainer + Report Commentary). NL query, hedge rationale, breach narrative, compliance auto-summary are explicitly deferred.

## CI/CD & guardrail approvals (pre-approved here)

Per CLAUDE.md guardrails, the following are explicitly approved in this plan so subagents don't stop mid-loop:

- **New service**: `ai-insights-service` (Python). Approved.
- **New dependencies in `ai-insights-service/pyproject.toml`**: `claude-agent-sdk`, `fastapi`, `uvicorn`, `pydantic`, plus pytest tooling already used by `risk-engine`. Approved.
- **`deploy/docker-compose.services.yml`** + **`deploy/helm/kinetix/charts/ai-insights-service/`** — new service entries with the `~/.claude:/root/.claude:ro` volume mount. Deployment configs, not CI/CD pipelines. Approved.
- **No CI/CD pipeline file changes anticipated** (`.github/workflows/*`). If a subagent discovers it must touch one, STOP and flag.
- **No new Kafka topics, no new DB tables, no new API contracts in other services**.

## Out of scope

- Natural-language portfolio query in the Cmd+K command palette.
- AI rationale text on `HedgeRecommendationPanel`.
- AI narrative on `BreachBanner` / limit-breach alerts.
- Compliance auto-summary on the Regulatory tab.
- A public hosted demo backed by live LLM calls. Hosted demos always run DEMO_MODE.
- Embeddings, vector stores, RAG, or any kind of retrieval over the codebase.
- Touching `risk-engine/src/kinetix_risk/ml/*` (anomaly detector, vol forecaster, credit PD). Those are quant ML, not LLM.
- Screenshots for the README. The user will capture these post-loop.

## Execution plan

### PR 0 — Process surface polish (docs only, no product code)

- [x] 0.1 Create `docs/adr/README.md` indexing every file matching `docs/adr/ADR-*.md` (or whatever the actual file naming is) in numeric order: `- ADR-XXXX: <title from first H1> — <one-line summary scanned from the abstract/summary section if present, else first non-heading line>`. Generated programmatically by reading file headers, not hand-curated.
      Acceptance: `test -f docs/adr/README.md && [ "$(grep -c '^- ADR-' docs/adr/README.md)" -ge 30 ]`
- [x] 0.2 Create `specs/README.md` indexing every `specs/*.allium` file, grouped by domain (core / trading / risk / regulatory / operations — infer from filename), each entry `- <filename>: <one-line summary from the file's top-of-file comment or first `entity`/`rule` declaration>`. Prefix the index with a 100–150 word "what is Allium and why we use it" paragraph that links to `docs/HOW_IT_WAS_BUILT.md`.
      Acceptance: `test -f specs/README.md && [ "$(grep -c '\.allium' specs/README.md)" -ge 20 ]`
- [x] 0.3 Create `docs/HOW_IT_WAS_BUILT.md` — single-page narrative (~500–700 words) of the AI-assisted-dev workflow: Claude Code as the IDE; Allium DSL as spec source-of-truth; the `/distill → /weed → /propagate` loop; `/work-plan` + `/loop` for autonomous execution; sub-agents (architect / quant / trader / qa / security / sre / etc.) as on-demand domain experts; memory persistence in `.claude/memory/`; worktrees for parallel work. One ASCII or Mermaid diagram showing the loop. Link out to `docs/evolution-report.md`, `docs/adr/README.md`, `specs/README.md`, `.claude/agents/`, `.claude/skills/`.
      Acceptance: `test -f docs/HOW_IT_WAS_BUILT.md && [ "$(wc -w < docs/HOW_IT_WAS_BUILT.md)" -ge 400 ]`
- [x] 0.4 Add a "Built with Claude Code" hero section to the top of `README.md` (above any existing content). Include: one-line pitch, bullet stats (auto-computed and inlined as numbers: total commits via `git rev-list --count HEAD`, count of services from `settings.gradle.kts`, count of `specs/*.allium`, count of `docs/adr/ADR-*.md`, weeks elapsed from first commit), and three "where to look next" links to `docs/HOW_IT_WAS_BUILT.md`, `docs/adr/README.md`, `specs/README.md`.
      Acceptance: `grep -q 'Built with Claude Code' README.md && grep -q 'HOW_IT_WAS_BUILT.md' README.md && grep -q 'docs/adr/README.md' README.md && grep -q 'specs/README.md' README.md`
- [x] 0.5 Refresh `docs/evolution-report.md` by re-invoking the `/evolution-report` skill output and overwriting the file. Add a final "Where it stands today (2026-05-18)" paragraph summarising current scope: 12 services + `ai-insights-service` planned, 36+ ADRs, 24 Allium specs, ~2,090 commits, AI features in v1.
      Acceptance: `grep -q '2026-05-18' docs/evolution-report.md`

### PR 1 — `ai-insights-service` Python scaffold

- [x] 1.1 Scaffold `ai-insights-service/` Python module mirroring `risk-engine/` shape: `pyproject.toml` (with `claude-agent-sdk`, `fastapi`, `uvicorn`, `pydantic`, `pytest`, `pytest-asyncio`, `httpx` for TestClient; markers `unit`/`integration` matching `risk-engine`), `src/kinetix_insights/__init__.py`, `src/kinetix_insights/app.py` exposing a FastAPI app with `/health` and `/ready` endpoints. Add a `Makefile` matching `risk-engine` conventions. No business logic.
      Acceptance: `cd ai-insights-service && uv sync && uv run pytest -m unit && uv run python -c "from kinetix_insights.app import app; assert app"`
- [x] 1.2 Add `InsightRequest` and `InsightResponse` pydantic models in `src/kinetix_insights/models.py`. `InsightRequest` carries `kind: Literal["var", "report"]` plus a `payload: dict[str, Any]`. `InsightResponse` carries `narrative: str`, `bullets: list[str]`, `model: str`, `mode: Literal["live", "canned"]`. Unit test asserts JSON round-trip for both shapes.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_models.py`
- [x] 1.3 Add `InsightClient` protocol in `src/kinetix_insights/insights_client.py` with `async def explain(request: InsightRequest) -> InsightResponse`. Add `CannedInsightClient` in `src/kinetix_insights/canned.py` that returns deterministic narratives keyed on `(request.kind, hash(canonical_json(payload)))`. Unit test asserts same input ⇒ same output and that canned mode returns `mode="canned"`.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_canned_client.py`
- [x] 1.4 Add `ClaudeAgentInsightClient` in `src/kinetix_insights/claude_agent_client.py` that wraps `claude-agent-sdk` and renders prompts via `src/kinetix_insights/prompts.py`. Unit test mocks the SDK surface (do NOT call the real SDK) to assert (a) prompt is constructed correctly per request kind, (b) response is parsed into `InsightResponse` with `mode="live"`, (c) any SDK exception raises `InsightClientUnavailable` so the app can fall back.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_claude_agent_client.py`
- [x] 1.5 Add `src/kinetix_insights/factory.py::build_client()` that returns `ClaudeAgentInsightClient` unless `DEMO_MODE=true` or instantiation raises, in which case it returns `CannedInsightClient`. Wire `app.py` to use the factory at startup. Unit test covers all three branches (`DEMO_MODE=true` ⇒ canned; SDK raises ⇒ canned; SDK ok ⇒ live).
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_factory.py`
- [x] 1.6 Add an in-process LRU cache (`functools.lru_cache` wrapper) around `InsightClient.explain` keyed by request hash, size 256. Unit test asserts identical requests return the same response without re-invoking the underlying client.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_cache.py`
- [x] 1.7 Add `ai-insights-service/README.md` documenting the host-auth model: native local dev uses `~/.claude/` automatically; Docker requires `~/.claude:/root/.claude:ro` volume mount; CI uses `DEMO_MODE=true`; no `ANTHROPIC_API_KEY`. Add a "Demo mode vs live mode" section showing the response shape difference (`mode` field) and how to flip.
      Acceptance: `test -f ai-insights-service/README.md && grep -q 'DEMO_MODE' ai-insights-service/README.md && grep -q '/root/.claude' ai-insights-service/README.md`
- [x] 1.8 Add `ai-insights-service/Dockerfile` matching `risk-engine/Dockerfile` shape (uv-based build), exposing port 8095. Add a `.env.example` entry at the repo root documenting `DEMO_MODE` and `CLAUDE_HOME`.
      Acceptance: `test -f ai-insights-service/Dockerfile && grep -q 'DEMO_MODE' .env.example`

### PR 2 — VaR Explainer endpoint + UI

- [x] 2.1 Add `POST /api/v1/insights/explain/var` route in `src/kinetix_insights/routes/var_explainer.py`. Accepts `VarExplainerRequest` (method, confidence, horizon_days, value_usd, top_contributors: list[{instrument, contribution_pct}], regime: str). Returns `InsightResponse`. Acceptance test in DEMO_MODE asserts the response shape, that bullets cover the top contributors, and that `mode == "canned"`.
      Acceptance: `cd ai-insights-service && DEMO_MODE=true uv run pytest tests/test_var_explainer_acceptance.py`
- [x] 2.2 Add `gateway/src/main/kotlin/com/kinetix/gateway/routes/InsightsRoutes.kt` exposing `POST /api/v1/insights/explain/var` that proxies the request body to `ai-insights-service` via Ktor `HttpClient` (mirror how `gateway` proxies to other backends — grep `gateway/src/main/kotlin/com/kinetix/gateway/` for an existing proxy route to copy from). Add `INSIGHTS_SERVICE_URL` env var with a sensible local default. Acceptance test stubs `ai-insights-service` with a fake HTTP server on a random port and asserts the proxy preserves request body and response body.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*InsightsRoutesAcceptanceTest"`
- [x] 2.3 Add `ui/src/api/insights.ts` — typed client (`explainVar(payload)`, `explainReport(payload)`) with `InsightResponse` type. Vitest unit test mocks `fetch` and asserts the request goes to `/api/v1/insights/explain/var` with the correct body shape.
      Acceptance: `cd ui && npm run test -- insights.test`
- [x] 2.4 Add `ui/src/components/AIInsightPanel.tsx` — reusable slide-over/card component rendering `narrative`, `bullets`, and a footer with `model` + a "Demo mode" badge when `mode === "canned"`. Vitest unit test covers loading skeleton, error state, demo-mode badge, and live-mode model display.
      Acceptance: `cd ui && npm run test -- AIInsightPanel`
- [x] 2.5 Wire an "Explain" button into `ui/src/components/VaRDashboard.tsx`'s VaR-gauge header. Click ⇒ fetch via `insights.ts` ⇒ render `AIInsightPanel`. Vitest unit test asserts button visible, click opens panel, loading state shown, narrative renders after resolve.
      Acceptance: `cd ui && npm run test -- VaRDashboard`
- [x] 2.6 Add `ui/e2e/var-explainer.spec.ts` Playwright test that mocks `/api/v1/insights/explain/var` per `ui/e2e/fixtures.ts` patterns, navigates to Risk tab, clicks "Explain", asserts panel renders with narrative + bullets + "Demo mode" badge. Extend `ui/e2e/fixtures.ts` with an `insightsMock` helper.
      Acceptance: `cd ui && npx playwright test var-explainer`
- [x] 2.7 Run `cd ui && npm run lint` to catch ESLint issues (per CLAUDE.md Testing Philosophy). Fix any reported errors.
      Acceptance: `cd ui && npm run lint`

### PR 3 — Report Commentary endpoint + UI

- [x] 3.1 Add `POST /api/v1/insights/explain/report` route in `src/kinetix_insights/routes/report_commentary.py`. Accepts `ReportCommentaryRequest` (template_id, report_date, summary_metrics: dict[str, float], top_drivers: list[{name, contribution_usd}], breaches: list[str]). Returns `InsightResponse`. Acceptance test in DEMO_MODE asserts shape and that bullets mention top drivers and breaches if present.
      Acceptance: `cd ai-insights-service && DEMO_MODE=true uv run pytest tests/test_report_commentary_acceptance.py`
- [ ] 3.2 Extend `gateway/src/main/kotlin/com/kinetix/gateway/routes/InsightsRoutes.kt` with `POST /api/v1/insights/explain/report` proxying to `ai-insights-service`. Extend the gateway acceptance test to cover both endpoints.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*InsightsRoutesAcceptanceTest"`
- [ ] 3.3 Extend `ui/src/api/insights.ts` with `explainReport(payload)`. Vitest unit test for the new method.
      Acceptance: `cd ui && npm run test -- insights.test`
- [ ] 3.4 Add an "AI Commentary" card to `ui/src/components/ReportsTab.tsx` that renders below the generated report. Uses the same `AIInsightPanel` component. Card shows a loading skeleton while the report generates, then fetches the commentary. Vitest unit test covers the integration.
      Acceptance: `cd ui && npm run test -- ReportsTab`
- [ ] 3.5 Add `ui/e2e/report-commentary.spec.ts` Playwright test mocking both `/api/v1/reports/generate` and `/api/v1/insights/explain/report`, generates a report, asserts the commentary card renders with narrative + bullets + demo-mode badge.
      Acceptance: `cd ui && npx playwright test report-commentary`
- [ ] 3.6 Run `cd ui && npm run lint`. Fix any errors.
      Acceptance: `cd ui && npm run lint`

### PR 4 — Deployment + demo integration

- [ ] 4.1 Add `ai-insights-service` to `deploy/docker-compose.services.yml` with the `~/.claude:/root/.claude:ro` host volume mount, exposing port 8095, and the same logging/health-check pattern as other services. Add `INSIGHTS_SERVICE_URL=http://ai-insights-service:8095` to the `gateway` service env block. Verify the compose file is still valid.
      Acceptance: `docker compose -f deploy/docker-compose.services.yml config --quiet`
- [ ] 4.2 Add `deploy/helm/kinetix/charts/ai-insights-service/` chart mirroring an existing per-service chart (e.g. `deploy/helm/kinetix/charts/risk-orchestrator/`): `Chart.yaml`, `values.yaml` (with a `claudeAuth.hostPath` value defaulting to `/root/.claude`), `templates/deployment.yaml` (with a `hostPath`-typed volume mount for the host's Claude credentials, gated by a `claudeAuth.enabled` value that defaults to `true`), `templates/service.yaml`, `templates/configmap.yaml`. Add the chart to the parent `deploy/helm/kinetix/Chart.yaml` dependencies.
      Acceptance: `helm lint deploy/helm/kinetix/charts/ai-insights-service && helm template deploy/helm/kinetix > /dev/null`
- [ ] 4.3 Update `.claude/skills/demo/SKILL.md` to mention the VaR Explainer and Report Commentary features in the demo walkthrough — what to click, what to expect to see in demo mode vs live mode.
      Acceptance: `grep -q 'Explain' .claude/skills/demo/SKILL.md && grep -q 'AI Commentary\|AI commentary' .claude/skills/demo/SKILL.md`
- [ ] 4.4 Add a brief "AI features" section to `README.md` (below the hero) describing the two features and linking to `ai-insights-service/README.md` for the host-auth + DEMO_MODE explanation.
      Acceptance: `grep -q 'AI features\|VaR Explainer' README.md && grep -q 'ai-insights-service/README.md' README.md`
- [ ] 4.5 Final full-stack test sweep: run unit + acceptance tests across all touched modules to confirm green.
      Acceptance: `./gradlew :gateway:test :gateway:acceptanceTest && cd ai-insights-service && uv run pytest && cd ../ui && npm run test && npm run lint`

## Verification — end-to-end (after `/loop` completes)

1. **Phase 0 verification** (process polish): open `README.md`, see the hero with auto-computed stats; click through to `docs/HOW_IT_WAS_BUILT.md`, `docs/adr/README.md`, `specs/README.md` — all links resolve, evolution report is current to today.
2. **Phase 1 verification, DEMO_MODE** (works for anyone, no auth needed): `DEMO_MODE=true ./deploy/redeploy.sh`, then run `/demo equity long/short`, open `https://kinetixrisk.ai`, click Risk tab, click "Explain" on the VaR gauge — panel shows canned narrative with "Demo mode" badge. Go to Reports tab, generate a report, see the AI Commentary card.
3. **Phase 1 verification, live via Claude Code subscription**: ensure `claude` CLI is installed and authenticated on the host (`claude --version` works), redeploy with the `~/.claude:/root/.claude:ro` volume mount on `ai-insights-service`, unset `DEMO_MODE`, repeat above — panel shows live narrative streamed from the Agent SDK; badge shows model name.
4. **Full test suite**: `./gradlew test acceptanceTest && ./gradlew :end2end-tests:end2EndTest && cd risk-engine && uv run pytest && cd ../ai-insights-service && uv run pytest && cd ../ui && npm run test && npx playwright test && npm run lint` — all green.

## Reuse — what already exists that this plan leans on

- **Python service shape**: `risk-engine/` (uv, pyproject, src layout, pytest markers, Dockerfile) — closest peer.
- **Gateway HTTP proxy pattern**: existing routes in `gateway/src/main/kotlin/com/kinetix/gateway/routes/` already proxy to backends; copy whichever route handler best fits.
- **gRPC/HTTP fake-server pattern for acceptance tests**: documented in `CLAUDE.md` "Project Conventions"; reuse for the gateway → `ai-insights-service` proxy test.
- **Playwright fixture pattern**: `ui/e2e/fixtures.ts` — extend with an `insightsMock` helper.
- **Demo scenarios**: `.claude/skills/demo/SKILL.md` — five existing scenarios; the new features ride on the existing "Equity Long/Short" scenario.
- **Helm chart template**: any per-service chart under `deploy/helm/kinetix/charts/`.
- **`/evolution-report` skill**: regenerates `docs/evolution-report.md` from `~/.claude/history.jsonl`.
