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

## Testing

```bash
uv run pytest                  # everything
uv run pytest -m unit          # fast unit tests
```

## Where to look next

- [`../docs/HOW_IT_WAS_BUILT.md`](../docs/HOW_IT_WAS_BUILT.md) — the AI-assisted-dev workflow that produced this service
- [`../README.md`](../README.md) — Kinetix project root README
- [`../plans/ai-v1.md`](../plans/ai-v1.md) — the multi-PR plan this service is part of
