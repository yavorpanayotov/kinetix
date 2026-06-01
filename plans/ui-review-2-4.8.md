# Kinetix UI Review — https://kinetixrisk.ai (demo deployment)

**Reviewer:** Claude (Opus 4.8) · **Date:** 2026-06-01 · **Method:** live-site walkthrough
driven by headless Chromium (Playwright 1.58), plus direct gateway-API probes and the
Grafana HTTP API. No mocks — every result below is from the running deployment.

> **TL;DR — the platform looks like the real deal.** All 12 tabs load, no page crashes,
> no JS errors, **no mixed content, valid Let's Encrypt TLS** (verify_result=0). Scenarios,
> FRTB, the stress grid, Grafana dashboards and live logs all work. **One flagship feature is
> broken for the demo (the AI Copilot — P0) and a handful of areas render empty due to
> demo-seed gaps (P1/P2).** Fixing the Copilot env var and re-seeding a few datasets gets this
> to a flawless demo.

---

## Fix status (2026-06-01, committed to `main`)

All fixes are committed to `main`. **The live site will not reflect them until a redeploy +
demo reseed** — Docker was unreachable from the review environment, so I could not redeploy or
verify against the live stack. UI fixes are verified offline (Vitest + Playwright with mocked
APIs, typecheck, lint); backend seed fixes are verified at the unit level (`:demo-orchestrator:test`
green) and are deploy-gated. **To make the live demo reflect these: run `/deploy` (or
`deploy/redeploy.sh`) on the deployment host, then trigger the demo-orchestrator bootstrap/reset.**

| Issue | Fix | Verification | Live effect |
|---|---|---|---|
| **kx-0uvb** P0 Copilot ungrounded | `docker-compose.services.yml` defaults `ai-insights-service DEMO_MODE=true` (overridable via `.env`) → grounded `CannedInsightClient` | config; canned path already tested | **on redeploy** |
| **kx-r2vw** P3 System labels | `SystemDashboard` friendly labels for regulatory/audit + title-case fallback | Vitest ✓ | **on redeploy** (UI) |
| **kx-kzbs** P1 Regulatory 404 | UI: `useRegulatory` treats `/frtb/.../latest` 404 as absent (no console error); Backend: `FrtbSeedJob` precomputes latest FRTB per book at bootstrap | Vitest+Playwright ✓ (UI); unit ✓ (job) | UI **on redeploy**; 200-on-`/latest` after reseed |
| **kx-v4j3** P1 Intraday charts empty | UI: P&L + VaR charts fall back to most-recent session w/ "Last session" badge when today is empty | Vitest+Playwright ✓ | **on redeploy** — fixes the symptom even with stale seed data |
| **kx-6o89** P2 Counterparty $0 | `CounterpartyRotation` spreads exposure across all 30 w/ instrument-type eligibility | unit ✓ | after reseed (trade tape) |
| **kx-l8s7** P2 KRD empty | `KrdSnapshotSeedJob` warms on-demand KRD for bond-holding books at bootstrap | unit ✓ | after reseed. **Note:** `daily_krd_snapshots` (V51) is dead code — KRD route is on-demand only |
| **kx-kjse** P2 Scenarios cold-open | ✅ **DONE** (approved persist+fetch) — orchestrator V72 `latest_stress_batches` + repo, POST `.../batch` persists, new GET `.../batch`; gateway proxy; UI fetches on mount and renders the grid without Run All | orchestrator+gateway unit ✓, UI tsc/lint/Vitest 12 ✓, Playwright cold-open 10 ✓ (repo IntegrationTest deploy-gated) | populates **after reseed** (bootstrap sweep now persists a latest batch) |

**Verification run on `main`:** `:demo-orchestrator:test` (192) green · UI `tsc` clean · `npm run
lint` 0 errors · affected Vitest 120/120 · affected Playwright 22/22.

**Two follow-ups surfaced during the fix work (out of the original review scope):**
- `daily_krd_snapshots` (migration V51) is referenced by no code — either wire the KRD route to
  persist/read it, or drop the migration. (KRD currently computes on-demand.)
- kx-kjse needs a product/architecture decision on how the Scenarios tab should populate on cold
  open (persist-and-fetch vs auto-run-on-mount vs improved empty state).

---

## Verdict by area

| Area | State | Notes |
|---|---|---|
| TLS / HTTPS | ✅ Clean | Valid LE cert (CN=kinetixrisk.ai, exp 2026-08-05), `ssl_verify=0`, **zero** `http://` requests, **zero** mixed-content warnings across the whole walk |
| Auth / personas | ✅ Works | Demo auth (no Keycloak login); persona switcher (Firm / Multi-Asset / role badges) renders; logged in as RISK MANAGER |
| Positions | ✅ Strong | Firm Summary (NAV $5.57B), currency breakdown, 25 positions, risk metrics, CSV export, column chooser |
| Trades (4 sub-tabs) | ✅ Strong | Blotter, Place Order form, **Execution Cost (very rich)**, Reconciliation all render |
| P&L | ⚠️ Partial | Attribution + waterfall + SOD baseline work; **Intraday P&L chart empty (P1)**; benchmark-attribution empty until a benchmark is chosen |
| Risk (4 sub-tabs) | ⚠️ Partial | Dashboard, Intraday, Run Compare, Market Data all load; **KRD panel always empty (P2)** |
| EOD History | ✅ Works | Renders content |
| Scenarios | ✅ Works | "Run All Scenarios" populates a full historical stress grid (GFC, COVID, Black Monday…); empty *until* run (P2 polish) |
| Counterparty Risk | ⚠️ Partial | 30 counterparties listed with PFE/CVA + "AGREEMENT EXPIRED / BLOCK NEW TRADES" badges, but **24/30 show $0.00 exposure (P2)** |
| Regulatory (FRTB) | ⚠️ Partial | **Loads empty + 404 in console (P1)**; after "Calculate FRTB" → full SbM/DRC/RRAO breakdown by risk class + CSV/XBRL export |
| Reports | ✅ Works | Template picker, recent reports list with COMPLETE status + Download |
| Activity | ✅ Works | Renders content |
| Alerts | ✅ Strong | 50 notifications, badge on tab, breach banner with "Need a hedge?" CTA |
| System | ✅ Strong | "All Systems Operational", 11 services READY, observability deep-links to Grafana |
| Command palette (⌘K) | ✅ Works | Opens, searchable |
| **AI Copilot ("Ask Kinetix")** | ❌ **Broken for demo (P0)** | Answers every question with *"I don't have access to any portfolio data / risk system"* and leaks its real tool environment |
| Grafana | ✅ Strong | 25 dashboards, 7 datasources, 16/16 Prometheus targets up, Loki logs live |

The **only** network error in the entire UI walk was the single Regulatory FRTB 404 (below).
No `pageerror`, no failed requests elsewhere, no 5xx.

---

## Issues (prioritised)

### P0 — AI Copilot is not grounded; gives "I can't access your data" and leaks its tool list
**Symptom.** Asking the Copilot (header "Ask Kinetix" / ⌘K) any question — including the
*curated built-in demo prompts* ("Which risk limits are in breach today?", "Which positions
contribute the most to portfolio risk?") — returns answers like:

> *"I don't have access to any portfolio data, positions, or risk analytics in this
> conversation… the available tools are for code/file operations, scheduling, and web fetches —
> not portfolio analytics."*

No citations, no demo badge, no grounding. For the showcase audience this is the worst possible
failure: the flagship AI feature both fails to answer *and* leaks that it's a generic coding agent.

**Root cause (confirmed in code).** The Copilot is `ai-insights-service`
(FastAPI + Claude Agent SDK), endpoint `POST /api/v1/insights/chat`.
`ai-insights-service/src/kinetix_insights/factory.py`:
- `DEMO_MODE=true` → `CannedInsightClient` → grounded narratives + citations + demo badge.
- otherwise → `ClaudeAgentInsightClient` → live Claude Agent SDK with **only its default tools**
  (Read/Write/Bash/WebFetch/Task/scheduling) and **no Kinetix risk-data tools wired in**.

`docker-compose.services.yml` defaults `DEMO_MODE: "${DEMO_MODE:-false}"`. The deployed UI is
demo-mode (`VITE_DEMO_MODE=true`) but **the ai-insights-service is running with `DEMO_MODE`
unset/false**, so it falls into the ungrounded live-agent path.

**Fix (smallest).** Deploy `ai-insights-service` with `DEMO_MODE=true` for the public demo. That
flips it to `CannedInsightClient`, which is exactly what the demo UX is designed around
(grounded VaR/P&L/limit narratives, `command-palette-copilot-citations`,
`command-palette-copilot-demo-badge` — all asserted by the e2e specs).
**Fix (proper, longer-term).** If a *live* agent is wanted in demo, provision the SDK agent with
Kinetix risk-data tools/MCP (gateway read endpoints) and a system prompt that forbids the generic
"I have no tools" fallback. Until then, `DEMO_MODE=true` is the right demo posture.

---

### P1 — Regulatory tab loads empty and emits a 404 console error
**Symptom.** Opening **Regulatory** shows "Click Calculate FRTB to compute risk charges" and the
console logs `404` for `GET /api/v1/regulatory/frtb/{book}/latest` (verified 404 for every book:
balanced-income, fixed-income, multi-asset, …). It's the *only* error in the whole UI walk.

**Why.** No FRTB result is precomputed/seeded, so the "latest" probe 404s. Clicking
**Calculate FRTB** works perfectly → full Total Capital ($1.7M), SbM/DRC/RRAO split, SbM-by-risk-class
breakdown (GIRR, CSR_NON_SEC, EQUITY, COMMODITY, FX…), CSV + XBRL export.

**Fix.** Either (a) seed a "latest" FRTB result per book in the demo seed so the tab is populated on
load, or (b) have the UI treat a 404 from `/frtb/{book}/latest` as a clean "no result yet" empty
state without logging a console error. Prefer (a) for "real deal" feel.

---

### P1 — Intraday P&L and Intraday VaR charts are empty (stale demo data)
**Symptom.** P&L tab → "Intraday P&L" shows *"No intraday data yet — snapshots will appear as
prices update."* Header ticker shows `INTRADAY P&L —` (dash). Risk → Intraday similar.

**Why (confirmed).** `PnlTab.tsx` builds the query window from `new Date()` =
**today (2026-06-01) 00:00→23:59Z**. But the demo intraday snapshots only exist for
**2026-05-29** (verified: `/api/v1/risk/pnl/intraday/balanced-income?from=2026-05-29…` returns
snapshots; querying *today* returns none). The demo data is 3 days stale relative to "today",
so the chart's default window finds nothing. Same for `/api/v1/risk/var/{book}/intraday`.

**Fix.** Make the demo seed date-relative (seed intraday snapshots for "today" each
deploy/seed), **or** default the chart window to the latest available snapshot date when today is
empty. The first option keeps every time-windowed surface fresh.

---

### P2 — KRD (Key Rate Duration) panel is empty for every book
**Symptom.** Risk → Dashboard shows `krd-empty`.
**Why.** `GET /api/v1/risk/krd/{book}` returns `{"instruments":[],"aggregated":[]}` for **all**
books (balanced-income, fixed-income, macro-hedge, multi-asset all = 0 tenors). KRD is simply
unseeded across the board.
**Fix.** Seed key-rate-duration buckets (especially for rate-heavy books like `fixed-income` /
`macro-hedge`) so the KRD ladder renders.

---

### P2 — Scenarios tab is empty until "Run All Scenarios" is clicked
By design (compute-on-demand), and the run works great. But for a cold-open demo it reads as
empty. **Fix (polish):** pre-seed a "latest stress run" or auto-run on first view so the grid is
populated immediately.

---

### P2 — Counterparty Risk: 24 of 30 counterparties show $0.00 exposure
**Symptom.** The list is fully populated (30 names) but only 6 carry material exposure (CP-JPM
$6.5M, CP-CITI, CP-UBS, CP-BARC, CP-GS, CP-DB). The other 24 (CP-AAPL, CP-BA, CP-BBVA, CP-BLK,
CP-BNP…) are $0.00 / $0.00 / —. Note commit `99fe721b` ("seed counterparty exposure for all 30")
created the reference rows, but no positions/trades exist against the other 24 → zero exposure.
**Fix (polish):** seed token exposure/PFE for the long tail, or collapse/paginate zero-exposure
names, so the table doesn't look padded below the fold. Verify the deployed image post-dates
`99fe721b` (committed today 11:00) — a redeploy may be needed regardless.

---

### P3 — Cosmetic / minor
- **System tab labels inconsistent:** `regulatory-service` and `audit-service` show raw
  service names while peers use friendly labels ("Gateway", "Position Service"). Add display names.
- **Grafana "No data" panels:** P&L dashboard has 7, Trade Flow has 3 (Risk Overview and
  Service Logs have **0**). The P&L ones are most likely the same intraday-staleness gap — re-check
  after the P1 intraday fix.
- **Log noise:** Service Logs dashboard shows **620K WARN / 2.22K ERROR over 7 days**. Volume is
  fine for a "busy" feel, but worth a glance that warnings aren't masking something real.

---

## What works well (evidence)

- **No HTTPS issues at all.** Valid LE cert, `ssl_verify_result=0`, no mixed content, no cert warnings.
- **All 12 tabs load with content** (text lengths: positions 3.9K, trades 4.7K, risk 10.1K,
  alerts 11.2K, etc.); **0 page errors, 0 failed requests** apart from the one Regulatory 404.
- **Trades** — all four sub-tabs render; Execution Cost is data-dense (TCA).
- **Scenarios** — "Run All" populates a full historical stress grid (Rates Shock 2022, Sept 2001,
  Dotcom, Black Monday 1987, Taper Tantrum, GFC 2008, COVID 2020, Brexit, Volmageddon…) with
  Base/Discounted Shift, multiplier, and P&L impact columns.
- **Regulatory FRTB** — full SbM/DRC/RRAO capital breakdown with CSV + XBRL export after Calculate.
- **System** — "All Systems Operational", 11 services READY, observability deep-links.
- **Grafana** — 25 dashboards across overview/support/health folders; 7 datasources (Prometheus,
  Loki, Tempo + Postgres). **16/16 Prometheus targets up**, 609 metric names incl. business metrics
  (`risk_var_value`, `risk_var_expected_shortfall`, …). **Loki logs are live** (most recent entry
  0.0h old) across 12 services. Risk Overview dashboard renders VaR gauges/trend/ES/component
  breakdown at ~1.09 req/s; Service Logs dashboard shows volume + error/warn counts + a populated
  log-lines table. Both with **0 "No data" panels**.

---

## Recommended action checklist

- [ ] **P0** Set `DEMO_MODE=true` on `ai-insights-service` in the demo deployment (redeploy) so the
      Copilot uses `CannedInsightClient` (grounded answers + citations + demo badge). Re-test the 5
      built-in prompts.
      Acceptance: `node` Playwright check — built-in Copilot prompt returns a grounded answer with a
      visible `command-palette-copilot-demo-badge` and ≥1 citation, no "I don't have access" text.
- [ ] **P1** Seed a "latest" FRTB result per book (or suppress the 404→empty path in the UI) so the
      Regulatory tab is populated on load with no console error.
      Acceptance: `curl -sk https://api.kinetixrisk.ai/api/v1/regulatory/frtb/balanced-income/latest` → 200.
- [ ] **P1** Make intraday demo data date-relative (seed today's intraday P&L/VaR snapshots) or
      default the chart window to the latest snapshot date.
      Acceptance: P&L tab "Intraday P&L" chart renders a line; header `INTRADAY P&L` shows a value.
- [ ] **P2** Seed KRD buckets for rate-sensitive books.
      Acceptance: `curl …/api/v1/risk/krd/fixed-income` returns non-empty `aggregated`.
- [ ] **P2** Pre-seed / auto-run a latest stress run so Scenarios isn't empty on cold open.
- [ ] **P2** Seed token counterparty exposure for the long-tail names (or collapse zeros);
      verify deployed image post-dates `99fe721b`.
- [ ] **P3** Friendly display names for `regulatory-service` / `audit-service` on the System tab.
- [ ] **P3** Re-check Grafana P&L / Trade Flow "No data" panels after the intraday fix.

---

## Appendix — how this was tested

- Drove the **live** site headless (Chromium, `ignoreHTTPSErrors:false` so cert/mixed-content
  issues would surface). Walked all 12 tabs + Trades(4)/Risk(4) sub-tabs, ran scenarios, calculated
  FRTB, exercised the Copilot (free-form + built-in prompts) and the command palette. Captured every
  `console.error`, `pageerror`, `requestfailed`, response ≥400, and any `http://` request per tab.
- Probed the gateway API directly (`/api/v1/books`, `/regulatory/frtb/*/latest`,
  `/risk/pnl/intraday/*`, `/risk/var/*/intraday`, `/risk/krd/*`, `/books/*/attribution`) to separate
  "no data seeded" from "compute-on-demand by design".
- Queried Grafana's HTTP API (datasources, dashboard search, Prometheus `up` / metric names, Loki
  recency) and rendered representative dashboards to count "No data" panels.
- Screenshots captured under `/tmp/kx-review/shots/` (transient).
