# Kinetix Insights Service

LLM-powered explanations for Kinetix risk surfaces (VaR Explainer, Report Commentary). Routes LLM calls through the host's Claude Code subscription via the Claude Agent SDK — no `ANTHROPIC_API_KEY`, no per-token spend.

## Quickstart

```bash
cd ai-insights-service
uv sync
uv run uvicorn kinetix_insights.app:app --port 8095
```

Shortcuts (see `Makefile`):

- `make run` — start the FastAPI app via uvicorn
- `make test` — run the full pytest suite
- `make test-unit` — fast unit tests only (`-m unit`)

Sanity-check endpoints:

- `GET /health` — liveness probe (returns `200 OK` once the app is up)
- `GET /ready` — readiness probe (returns `200 OK` once the insights client is built and the cache is warm)

## Host-auth model

This service does **not** use `ANTHROPIC_API_KEY`. The Claude Agent SDK reuses the user's Claude Code subscription by reading credentials from `~/.claude/` on the host. Three deployment contexts:

### Native local dev

The Claude Agent SDK reads `~/.claude/` automatically. Just have the `claude` CLI installed and authenticated:

```bash
claude --version   # must succeed
```

No env vars required — `uv run uvicorn …` Just Works.

### Docker / docker compose

Bind-mount the host's Claude config into the container as read-only:

```yaml
services:
  ai-insights-service:
    image: kinetix/ai-insights-service:latest
    volumes:
      - ~/.claude:/root/.claude:ro
    ports:
      - "8095:8095"
```

Without this mount the SDK cannot reach an authenticated CLI and the service falls back to demo mode (see below).

### CI / Playwright

Always set `DEMO_MODE=true`. Tests must never depend on host auth — CI runners have no `~/.claude/` and no interactive subscription.

```bash
DEMO_MODE=true uv run uvicorn kinetix_insights.app:app --port 8095
```

**No `ANTHROPIC_API_KEY` is needed or used anywhere.** The Agent SDK exclusively uses the user's Claude Code subscription via the local CLI.

## Demo mode vs live mode

Every `InsightResponse` carries a `mode` field so callers (and the UI) can tell which client served the request.

Live mode (Agent SDK reached an authenticated CLI):

```json
{
  "narrative": "...",
  "bullets": ["..."],
  "model": "claude-sonnet-4-6",
  "mode": "live"
}
```

Canned mode (deterministic templates, no LLM call):

```json
{
  "narrative": "...",
  "bullets": ["..."],
  "model": "canned",
  "mode": "canned"
}
```

### How to flip

- **Demo mode (canned)**: `DEMO_MODE=true uv run uvicorn kinetix_insights.app:app --port 8095`
- **Live mode (default)**: unset / omit `DEMO_MODE` and ensure `claude --version` works for the user running the process.

### Fallback behaviour

If `DEMO_MODE` is unset but the Agent SDK can't reach an authenticated CLI (e.g. running in Docker without the `~/.claude` mount, or on a host where `claude` is not installed), `factory.build_client()` falls back to the canned client and responses come back with `mode="canned"`. This is by design — the UI uses the `mode` field to show a "Demo mode" badge so operators can see at a glance that they are not looking at real LLM output.

## Architecture summary

Key modules under `src/kinetix_insights/`:

- `app.py` — FastAPI app; `lifespan` builds the insights client once at startup
- `factory.py::build_client()` — chooses live (Agent SDK) vs canned based on `DEMO_MODE` and CLI availability
- `claude_agent_client.py` — Claude Agent SDK wrapper that produces `InsightResponse` with `mode="live"`
- `canned.py` — deterministic narrative templates for demo mode (`mode="canned"`)
- `cache.py` — in-process LRU (size 256) keyed by a hash of the request payload
- `prompts.py` — per-kind prompt renderers (e.g. VaR Explainer, Report Commentary)
- `models.py` — `InsightRequest`, `InsightResponse`

## v2 demo flow

AI v2 ships the **Kinetix Copilot** — a proactive morning brief, intraday push
alerts, ⌘K free-form ask, and saved queries (see [`../plans/ai-v2.md`](../plans/ai-v2.md)
and [`../docs/adr/ADR-0036-ai-copilot-architecture.md`](../docs/adr/ADR-0036-ai-copilot-architecture.md)).
Every v2 endpoint has a `Canned*Client` that replays a deterministic fixture, so
the whole demo runs end-to-end with **zero live SDK calls** when `DEMO_MODE=true`.

### What `/demo` pre-stages

The `/demo` orchestrator (`.claude/skills/demo/SKILL.md`, step 7) pre-stages the
three deterministic v2 artifacts so the 90-second demo script never blocks on a
live Claude call:

| Artifact | Served by | Fixture |
| -------- | --------- | ------- |
| Morning brief | `CannedBriefClient` → `GET /api/v1/insights/brief/today` | `src/kinetix_insights/fixtures/demo_brief.json` |
| Queued intraday push | `CannedIntradayPushGenerator` → `/internal/copilot/push` → `/ws/copilot` | `src/kinetix_insights/fixtures/demo_intraday_push.json` |
| Saved-query result | `CannedCopilotChatClient` → `POST /api/v1/insights/queries/{id}/run` | `src/kinetix_insights/fixtures/chat_transcripts/*.json` + `src/kinetix_insights/queries/*.json` |

"Pre-staging" is just ensuring those canned fixtures exist and are wired: with
`DEMO_MODE=true`, `factory.build_client()` selects the canned clients and the
demo artifacts become deterministically available — no host `~/.claude/` mount
and no SDK reachability required.

### Bring it up

```bash
# 1. Seed demo portfolios / positions / risk results
/demo            # (Claude Code slash command — see .claude/skills/demo/SKILL.md)

# 2. Start ai-insights-service in canned mode (no host auth needed)
cd ai-insights-service
DEMO_MODE=true uv run uvicorn kinetix_insights.app:app --port 8095

# Or bring the whole platform up with the demo Helm values (DEMO_MODE=true):
./deploy/redeploy.sh
```

### The 90-second demo script

1. **Morning brief lands (~20 s).** Open the UI, expand the notification strip.
   A `<MorningBriefCard>` shows the overnight risk story for `fx-main` — VaR,
   P&L attribution, breaches, limits, Greeks — every number carrying a citation.
   The **"Demo mode"** badge confirms the brief is canned.
2. **Intraday push fires (~20 s).** A critical VaR-breach push arrives over
   `/ws/copilot` and renders as an `<IntradayPushItem>` (Zap icon, headline,
   context bullets, source citations) at the top of the inbox.
3. **⌘K free-form ask (~30 s).** Press ⌘K, type a free-form question
   ("why did my VaR move overnight"). The copilot streams a token-by-token
   answer below the command list with a `<CitationList>` and the "Demo mode"
   badge — the canned SSE transcript replays deterministically.
4. **Saved-query chip (~20 s).** Click a built-in saved-query chip
   (e.g. *Limit breaches*, *VaR week drivers*) from the inbox or the ⌘K
   "Copilot" group. It routes through the same canned chat client and streams
   the same deterministic answer.

The browser-level proof of this script is the Playwright spec
[`../ui/e2e/copilot-demo-walkthrough.spec.ts`](../ui/e2e/copilot-demo-walkthrough.spec.ts),
which walks all four beats against the canned mock routes and asserts no
request escapes to a live SDK path. Run it with:

```bash
cd ui && npx playwright test e2e/copilot-demo-walkthrough.spec.ts
```

## Testing

```bash
uv run pytest                  # everything
uv run pytest -m unit          # fast unit tests
```

## Where to look next

- [`../docs/HOW_IT_WAS_BUILT.md`](../docs/HOW_IT_WAS_BUILT.md) — the AI-assisted-dev workflow that produced this service
- [`../README.md`](../README.md) — Kinetix project root README
- [`../plans/ai-v1.md`](../plans/ai-v1.md) — the multi-PR plan this service is part of
