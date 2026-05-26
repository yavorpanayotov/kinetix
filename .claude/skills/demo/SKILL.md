---
name: demo
description: Set up realistic demo data for Kinetix — portfolios, positions, trades, market data, and risk results for demonstrations or local development. Invoke with /demo optionally followed by a scenario (e.g. "equity portfolio", "multi-asset", "stress scenario").
user-invocable: true
allowed-tools: Read, Write, Edit, Glob, Grep, Bash, Task, WebFetch, WebSearch
---

# Demo Data Setup

You are setting up realistic demo data for Kinetix. The goal is to create a convincing, internally consistent dataset that showcases the platform's capabilities.

## Step 1 — Choose the scenario

If the user specified a scenario, use that. Otherwise offer these options:

1. **Equity Long/Short** — 50 positions across US/EU equities, demonstrating portfolio risk, sector concentration, and P&L
2. **Multi-Asset** — equities, options, fixed income across 3 currencies, demonstrating cross-asset risk aggregation and FX effects
3. **Options Book** — 30 equity options positions demonstrating full Greeks (delta, gamma, vega, theta, rho), vol surface, and cross-Greeks
4. **Stress Scenario** — a portfolio near limit breaches, demonstrating alerts, limit management, and stress testing
5. **Regulatory Demo** — positions with full audit trail, model governance records, and regulatory submission workflow

Ask the user to confirm before proceeding.

## Step 2 — Understand the data model

Read the current schema and API to understand how to insert data:

- Check position-service API for trade booking endpoints
- Check price-service API for market data ingestion
- Check gateway routes for available endpoints
- Check database migration files for current schema

```bash
# Find API route definitions
grep -r "route\|routing\|get(\|post(\|put(\|delete(" --include="*.kt" -l | grep -i route
```

## Step 3 — Generate the data

Create data that is:
- **Internally consistent** — prices, positions, and P&L numbers agree with each other
- **Realistic** — use real instrument names, realistic prices, plausible portfolio sizes
- **Time-aware** — include some historical trades to show lifecycle, not just current positions
- **Multi-portfolio** — at least 2 portfolios to demonstrate aggregation

### Market data
- Current prices for all instruments
- Historical prices for at least 5 days (for VaR calculation)
- Yield curve data (risk-free rates)
- Volatility data (for options)

### Trades and positions
- Mix of trade types (BUY, SELL, for options)
- Include at least one amended and one cancelled trade (for audit trail demo)
- Position sizes that produce meaningful risk numbers

### Risk results
- Trigger risk calculations after data is loaded
- Verify VaR, Greeks, and P&L numbers are reasonable

## Step 4 — Load the data

Create a script or series of API calls to load the data. Prefer API calls over direct database inserts to ensure all downstream effects (Kafka events, audit trail, risk recalculation) are triggered.

## Step 5 — Verify

After loading:
- [ ] Positions appear in the UI
- [ ] Prices are fresh
- [ ] Risk calculations complete
- [ ] P&L numbers are reasonable
- [ ] Audit trail has entries
- [ ] At least one alert rule is triggered

## Step 6 — Summary

Report:
- Scenario loaded
- Number of portfolios, positions, trades
- Key numbers to mention during demo (total VaR, largest position, P&L)
- Any setup steps needed (e.g. "start the risk-engine first")

## Step 7 — Pre-stage the v2 Copilot demo artifacts

AI v2 adds the **Kinetix Copilot** — proactive morning brief, intraday push,
⌘K free-form ask, and saved queries. The 90-second v2 demo must run
end-to-end with **zero live SDK calls**, so `/demo` pre-stages three
deterministic artifacts. Because v2's demo mode is driven by `DEMO_MODE=true`
plus the `Canned*Client`s, "pre-staging" means verifying the canned fixtures
exist and are wired — no extra seeding API calls are needed.

Run this check as part of `/demo`:

```bash
# All three v2 demo fixtures must be present and valid JSON.
for f in \
  ai-insights-service/src/kinetix_insights/fixtures/demo_brief.json \
  ai-insights-service/src/kinetix_insights/fixtures/demo_intraday_push.json; do
  python3 -c "import json,sys; json.load(open('$f')); print('ok: $f')"
done
ls ai-insights-service/src/kinetix_insights/fixtures/chat_transcripts/*.json
ls ai-insights-service/src/kinetix_insights/queries/*.json
```

The three pre-staged artifacts:

| Artifact | Canned client | Fixture | Surfaced at |
| -------- | ------------- | ------- | ----------- |
| Morning brief | `CannedBriefClient` | `fixtures/demo_brief.json` | `GET /api/v1/insights/brief/today` → `<MorningBriefCard>` |
| Queued intraday push | `CannedIntradayPushGenerator` | `fixtures/demo_intraday_push.json` | `/internal/copilot/push` → `/ws/copilot` → `<IntradayPushItem>` |
| Sample saved-query result | `CannedCopilotChatClient` | `fixtures/chat_transcripts/*.json` + `queries/*.json` | `POST /api/v1/insights/queries/{id}/run` → `<StreamingNarrative>` |

Then start `ai-insights-service` in canned mode so the factory selects the
canned clients:

```bash
cd ai-insights-service && DEMO_MODE=true uv run uvicorn kinetix_insights.app:app --port 8095
```

With `DEMO_MODE=true`, `factory.build_client()` picks the canned clients and
all three artifacts become deterministically available — no host `~/.claude/`
mount, no SDK reachability required. The full demo runbook (the four-beat
90-second script) lives in `ai-insights-service/README.md` under
**"v2 demo flow"**, and the browser-level proof is the Playwright spec
`ui/e2e/copilot-demo-walkthrough.spec.ts`.

## AI features (VaR Explainer + Report Commentary)

Two LLM-powered features ship in v1, both backed by `ai-insights-service`. Demo mode is on by default in `/demo`-seeded environments; flip `DEMO_MODE=false` and run with a host `~/.claude` mount for live mode.

### VaR Explainer

- **Where:** Risk tab → VaR gauge card header.
- **Action:** click the **Explain** button.
- **Expected:** an `AIInsightPanel` opens showing a short narrative explaining the current VaR result, 3–5 bullets calling out the top contributors, and a model footer.
  - In demo mode you'll see a **"Demo mode"** badge next to the model name; the narrative is canned and deterministic.
  - In live mode the badge is hidden and the model name reflects the Claude model used (e.g. `claude-sonnet-4-6`), with the narrative generated against the host's authenticated `claude` CLI via the Agent SDK.

### AI Commentary (Reports)

- **Where:** Reports tab, below the generated report output.
- **Action:** select a report template and click **Generate** (the existing flow). Once the report renders, an **AI Commentary** card appears beneath it — first as a loading skeleton, then populated.
- **Expected:** same `AIInsightPanel` shape as the VaR Explainer — narrative paragraph, bullets, and model footer. Demo-mode badge in canned mode, hidden in live mode.

### Demo mode vs live mode

| Mode | Flag | Behaviour |
| ---- | ---- | --------- |
| Demo | `DEMO_MODE=true` (default for `/demo`-seeded envs) | Canned narratives, deterministic output, no host auth required. "Demo mode" badge visible. |
| Live | `DEMO_MODE=false` with host `~/.claude` mounted | Real Claude responses via the Agent SDK using the host's authenticated `claude` CLI. Badge hidden, model name shown. |

## Live-demo vs background-mode tuning

The `demo-orchestrator` drips synthetic trades during trading hours. The
cadence is set by `DEMO_TRADE_CADENCE_SECONDS` and has two supported profiles:

| Profile | `DEMO_TRADE_CADENCE_SECONDS` | When to use |
| ------- | ---------------------------- | ----------- |
| **Background** (default) | `90` | Always-on demo cluster, nightly-reseeded environments, local dev. Avoids filling Postgres / Kafka. |
| **Live-demo** | `30` | Buyer walkthroughs and screen recordings — visible blotter activity within ~2 minutes of opening the UI. Do not leave running overnight. |

For a tight limit-breach demo on top of the live cadence (see kx-5q4):

- `DEMO_BREACH_BOOK=derivatives-book` (default) — book that gets tight limits.
- `DEMO_BREACH_VAR_FACTOR=0.50` (default) — VaR-limit factor on that book.
  Lower = breach sooner; standard books use 0.80.

**Switching to live-demo cadence:**

- Docker Compose: layer `docker-compose.live-demo.yml` on the stack.
- Helm: layer `deploy/helm/kinetix/values-live-demo.yaml` on top of
  `values-demo.yaml`.

Full rationale and command snippets in
[`demo-orchestrator/README.md`](../../../demo-orchestrator/README.md).

## Reminders

- Use realistic but not real company names if generating fictional data
- Ensure options have valid expiry dates in the future
- Include at least one position near a limit to show limit monitoring
- Create trades over multiple days to show historical trends
- Always trigger risk recalculation after loading positions
