# UI / Demo Review — https://kinetixrisk.ai (2026-05-29)

Full functional sweep of the live demo deployment, driven by Playwright (Chromium,
headless, 1600×1000) against the production host, plus direct gateway/Grafana API
probes. Persona: **risk_manager1** (default). Artefacts (screenshots, raw JSON, crawl
scripts) live in [`ui-review-4.8-artifacts/`](ui-review-4.8-artifacts/).

## Resolution (2026-06-01)

All four filed beads issues are fixed (commits on `main`); each takes effect on the
next redeploy of the relevant service / Grafana provisioning reload.

| Issue | Fix | Commit |
|---|---|---|
| **kx-oaf6** (P1) Reports `recent reports: 404` | Implemented the missing downstream `GET /api/v1/reports/recent` in risk-orchestrator (registered before `{outputId}`); empty table → `200 []`. Route/service/repo tests added. | `b1c08d8` |
| **kx-cygh** (P2) Grafana P95 + 5xx ratio "No data" | Enabled `percentilesHistogram(true)` on the gateway Ktor timer so `_bucket` series exist for the P95 panels; zero-filled the 5xx-ratio stat with `or vector(0)` (verified against live Prometheus). | `b757a50` |
| **kx-wrvc** (P2) Grafana P&L dashboard "No data" | Orchestrator now mirrors persisted P&L attribution onto `pnl_attribution_*` gauges via a scheduled publisher → populates 11/14 panels. | `23a22ec` |
| **kx-8kqk** (P3) Counterparty Risk 24/30 at $0.00 | Seeded tier-appropriate exposure for all 30 counterparties (tied to `CounterpartyTiers.ALL_IDS`). Needs a demo reset to apply on an already-seeded env. | `99fe721` |

Follow-up filed: **kx-v98m** — the 3 Dollar-Delta/Dollar-Gamma P&L panels still read
"No data" (need cash-greek sensitivities the attribution model doesn't carry).

The two console-noise items below (DemoBootstrapGate warning, Regulatory `/latest`
404) were documented as benign/by-design and were not filed as beads issues; left
as-is.

## TL;DR

The platform genuinely **looks like the real deal**. Valid HTTPS (Let's Encrypt,
nginx), no TLS errors, no mixed-content. All 12 top-level tabs load and render rich
synthetic data. FRTB calculation, stress scenarios, report generation, the AI copilot,
and position drill-downs all work with **zero API errors**. 25 Grafana dashboards exist,
Loki logs are streaming, Tempo is healthy, and the System tab reports every service
`READY`.

Found **one user-visible defect** (Reports → "Failed to fetch recent reports: 404"), a
**significant observability gap** (the Grafana P&L dashboard is entirely "No data", plus
empty P95-latency panels), and a few realism/polish items. None of these break the demo,
but the Reports error and the empty P&L dashboard are the two a prospect would notice.

## Environment

- `kinetixrisk.ai`, `api.kinetixrisk.ai`, `grafana.kinetixrisk.ai` all resolve to
  `152.67.156.167` (Oracle Cloud), fronted by **nginx + valid Let's Encrypt cert**.
- The app is built in **demo mode** (`VITE_DEMO_MODE=true`): bundle ships the demo
  personas (`risk_manager1`, "Switch persona", "Demo mode") and **no Keycloak** — so no
  login is required. `DemoAuthProvider` supplies persona headers.
- Local docker only runs **infra/observability** (postgres, redis, kafka, keycloak,
  prometheus, loki, tempo, grafana, alertmanager, otel-collector). The application
  services run on the **remote** host — `api.kinetixrisk.ai/health` → `{"status":"UP"}`.
- Stray local container `charming_yalow` (a `grafana/tempo:latest` started with
  `-config.file /etc/tempo/test.yaml`) is running unmanaged — not part of the `infra`
  compose project. Harmless but should be cleaned up.

## What works (verified)

| Area | Result |
|---|---|
| **Positions** | Firm Summary, 25 positions, USD/EUR currency breakdown w/ FX, NAV $5.55B, Book Δ / Book VaR cards, search + type filter, Details/Columns/Export CSV. |
| **Trades** | Full blotter (time, instrument, side, qty, filled, price, notional, status); sub-tabs Trade Blotter / Place Order / Execution Cost / Reconciliation. |
| **P&L** | Intraday P&L curve, EOD baseline marker, P&L Waterfall. |
| **Risk** | Live breach banners (3 CRITICAL VaR), Dashboard / Intraday / Run Scenario / Market Data sub-views, Market Risk panel ($227.4K VaR 1D 95%) with donut charts. |
| **EOD History** | VaR/ES trend chart + snapshot table (VaR, ES, Δ, P&L, status Promoted). |
| **Scenarios** | Stress library — GFC 2008, COVID 2020, Black Monday 1987, Taper Tantrum, Brexit, Volmageddon, LTCM, etc. — each with Base VaR, Stressed VaR, multiplier, P&L impact. "Run All Scenarios" executes cleanly. |
| **Counterparty Risk** | 30 counterparties, Net Exposure / Peak PFE / CVA; CP-DB shows "Agreement Expired" + "Block New Trades" badges (nice touch). *(see gap below)* |
| **Regulatory** | **Calculate FRTB works** → Total Capital $1,710,358.92, SbM / DRC / RRAO breakdown + per-risk-class SbM table (GIRR/CSR/Equity/Commodity/FX × Delta/Vega/Curvature). Download CSV enabled; XBRL gated until calc. |
| **Reports** | Template dropdown (Risk Summary, Stress Test Summary, P&L Attribution) + Generate work. *(Recent Reports panel broken — see below)* |
| **Activity** | Hash-chained audit trail of TRADE_BOOKED events (subject, book, user). |
| **Alerts** | Rule builder + rules table (VaR Breach, P&L Threshold, Limit, Concentration…) + live triggered breaches; tab badge shows 50 unread. |
| **System** | "All Systems Operational" — 11 services `READY`; Observability panel deep-links to 9 Grafana dashboards. |
| **AI Copilot ("Ask Kinetix")** | Opens, accepts a prompt, returns an answer with a self-verification caution banner (hallucination guard). No errors. |
| **Header** | Demo badge, persona switcher, Firm/portfolio scope, Multi-Asset, Frozen toggle, NORMAL status, dark-mode toggle, GitHub link (`github.com/panayotovk/kinetix`). |
| **Grafana** | 25 dashboards; anon access; loki/tempo/prometheus datasources healthy. Service Logs dashboard streams live (Error 564 / Warn 89.1k). Risk Overview & Risk Engine fully populated. |

## Findings / action items

Ordered by severity. Each box is independently committable; acceptance command on the
line below it.

### P1 — user-visible

- [x] **Reports tab shows a red "Failed to fetch recent reports: 404".**
  `GET /api/v1/reports/recent` → **404** on the live gateway, while `/reports/templates`
  (200) and `/reports/generate` work — both in the same `ReportRoutes.kt`. So the route
  file is deployed; the `recent` leg specifically 404s (gateway propagates a downstream
  404, or the live gateway predates the `recent` route). Two-part fix: (a) make the
  `RecentReportsPanel` render an empty state ("No reports generated yet") on 404 instead
  of a red error; (b) confirm `/api/v1/reports/recent` returns `200 []` when there are no
  reports (downstream + gateway), and redeploy the gateway.
  Acceptance: `cd ui && npm run test -- RecentReports` and `curl -sk -o /dev/null -w '%{http_code}' https://api.kinetixrisk.ai/api/v1/reports/recent` returns `200`.

### P2 — observability gaps (a prospect clicks through to Grafana)

- [x] **Grafana "P&L" dashboard is entirely "No data".** All 7 panels empty — Total P&L,
  P&L Trend, Unexplained P&L, Unexplained P&L Fraction, P&L by Book, P&L Attribution by
  Greek, Greek P&L Contribution. The P&L Prometheus metrics are not being emitted (or the
  panel queries don't match the exported metric names). Either wire the P&L metrics or
  fix the queries. Evidence: `shots/graf-pnl.png`.
  Acceptance: open `https://grafana.kinetixrisk.ai/d/kinetix-pnl/pandl?from=now-24h&to=now` and confirm 0 panels read "No data".

- [x] **P95 latency panels read "No data"** on API Gateway ("Latency Percentiles by Route
  P95", "Upstream Service-Call Latency P95", "Max P95 Latency" stat). Histogram metrics
  for latency are missing/sparse. Verify the `*_seconds_bucket` histograms are exported
  and that `histogram_quantile(...)` queries reference the right metric. Evidence:
  `shots/graf-api-gateway.png`.
  Acceptance: open `https://grafana.kinetixrisk.ai/d/kinetix-gateway/api-gateway?from=now-24h&to=now` and confirm the P95 panels render a series.

- [x] **Stat panels that should show 0 show "No data" instead** ("5xx Error Ratio", and a
  few on system-health/trade-flow/kafka-health). Classic empty-series-vs-zero: the query
  returns no series when the numerator is 0. Add `or vector(0)` / `OR on() vector(0)` (or
  a panel "No value → 0" mapping) so they read `0` rather than "No data".
  Acceptance: API Gateway "5xx Error Ratio" panel shows `0` (or `0%`) not "No data".

### P3 — demo realism / polish

- [x] **Counterparty Risk: 24 of 30 counterparties show $0.00 / $0.00 / —.** Only
  CP-JPM/CITI/UBS/BARC/GS/DB carry exposure; the rest (CP-AAPL, CP-BA, CP-BBVA, CP-BLK,
  CP-BNP, CP-BRDG, CP-CITDL, CP-CME…) are padding the list with zeros. Either seed small
  exposures across more counterparties or trim the demo list so the table reads as live
  book rather than a half-empty reference list. Evidence: `shots/tab-counterparty-risk.png`.
  Acceptance: Counterparty Risk table shows ≤ 3 zero-exposure rows.

- [ ] **`[DemoBootstrapGate] bootstrap-status unreachable — gate disabled`** warns in the
  console on **every** page load. `GET /demo/bootstrap-status` (UI origin) → 404; the gate
  is *designed* to fail open on 404, so behaviour is correct, but the `console.warn` is
  noise on a clean demo. Either serve a stub `/demo/bootstrap-status` (→ ready) at the
  edge, or downgrade the message to `console.debug`. (`DemoBootstrapGate.tsx:118`.)
  Acceptance: load `https://kinetixrisk.ai`, console shows no `[DemoBootstrapGate]` warning.

- [ ] **Regulatory logs a benign 404 on load** —
  `GET /api/v1/regulatory/frtb/balanced-income/latest` 404s because no cached FRTB result
  exists yet; the panel correctly shows "Click Calculate FRTB". Suppress the console error
  (treat 404 as "no cached result") so the console stays clean.
  Acceptance: switch to Regulatory tab, console shows no 404 error for `/regulatory/frtb/.../latest`.

## Out of scope (not defects)

- Grafana metrics only densely populated for roughly the last hour (gateway request-rate
  chart starts ~18:00). Consistent with low demo traffic / a recent service restart —
  monitor, not a fix.
- The copilot's "I couldn't verify that answer" banner is a **feature** (the answer-
  verification / hallucination guard), not a failure.
- The stray `charming_yalow` Tempo container is a *local* artefact and does not affect the
  remote demo — clean up opportunistically.

## How this was produced

- `ui-review-4.8-artifacts/crawl-review.mjs` — visits all 12 tabs, captures console
  errors, failed requests (≥400), empty-state signals, and per-tab screenshots.
- `ui-review-4.8-artifacts/crawl-interact.mjs` — exercises Calculate FRTB, Run All
  Scenarios, report generate, Ask Kinetix, position expand; then loads 8 Grafana
  dashboards and counts "No data" panels.
- Raw results: `crawl.json`, `interact.json`. Screenshots: `shots/` (28 PNGs).
- Re-run: `cd ui && node ../docs/plans/ui-review-4.8-artifacts/crawl-review.mjs` (Playwright
  1.58 + Chromium already installed).
