# UI Trader Review — `kinetixrisk.ai`

Reviewer: Marcus (senior trader persona), 2026-05-28
Method: signed in as `trader1`, walked every top-level tab and known sub-tab on the live demo via Playwright (`ui/trader-explorer.local.mjs` + `ui/trader-text.local.mjs`). Screenshots in `/tmp/trader-review/shots`, text dumps in `/tmp/trader-review/text`.

---

## What works

Worth saying first so nobody panics:

- **Service health is clean.** Zero 4xx/5xx, zero console errors across all 12 tabs. Gateway, position, risk-orchestrator, etc. all `READY`. That is non-trivial.
- **The information architecture is right.** Positions → Trades → P&L → Risk → EOD → Scenarios → Counterparty Risk → Regulatory → Reports → Activity → Alerts → System. That mirrors a real desk's mental model.
- **Right concepts surfaced on the Risk tab.** Standalone / marginal / incremental VaR, ES, diversification benefit, component breakdown, correlation matrix, factor decomposition, stress, liquidity, margin, limits — the skeleton of an institutional risk dashboard is there.
- **Persona switcher, persistent ticker strip, audit chain badge, alert deep-links.** All good craft.
- **EOD History chart and table layout** are clean and the right shape for daily risk review.
- **`Frozen` toggle and `Refresh` per-section** show somebody thought about a trader's need to lock the view during a P&L discussion.

The bones are good. The numbers on the bones are not.

---

## What does not work (the painful part)

Findings are grouped by severity. P0 are **data correctness** — they would make a real trader stop using the system. P1 are **cross-tab consistency**. P2 are workflow gaps. P3 is polish.

### P0 — Data correctness

1. **Per-instrument Greeks are not per-instrument.** On `Risk → Position Risk Breakdown`, *every* equity has Delta `-2,111,524.48`, Gamma `-445,120,486.75`, Vega `-1,645,799.29`. *Every* fixed-income has Delta `-2,167,868.28`. Etc. The per-instrument table is repeating the asset-class aggregate for each row. Cash equity gamma/vega is zero; a Treasury has DV01, not equity delta. This is unusable as a per-position view.
2. **DV01 column is empty (`—`) for every instrument**, including bonds and IRS. Without DV01 a rates trader can't size hedges.
3. **Per-instrument Theta and Rho are also empty (`—`)**, even though aggregate Theta `47,265.38` and Rho `-665.94` are shown above.
4. **Asset-class taxonomy bug — Treasuries booked as Equity.** `UST-5Y`, `UST-10Y`, and `PG` show under Asset Class = `Equity` on the Risk tab's per-instrument breakdown. Same instruments show `Type = Cash Equity` on the Trades blotter. Treasuries are not equities; this poisons every aggregation downstream.
5. **The "Equity-typed" Treasuries also show Mkt Value `$0.00`** on Risk → Position Risk Breakdown — yet the Trades blotter has dozens of fresh `UST-5Y/UST-10Y/PG` trades. Either position events aren't being applied or the join from trade → position is broken.
6. **Three different Risk totals on the same Risk tab.** Header VaR `$190.1K`; `Sum of books` shows `$13.4M`; Book Contributions table sums to roughly `$182M` (emerging-markets `$90M` + macro-hedge `$88M` + smaller); Factor Risk Decomposition `Total VaR $1K`. Four numbers, one truth. Today a trader cannot trust *any* of them.
7. **Counterparty exposure: two data sources, two answers.** Dedicated Counterparty Risk tab shows 6 names (JPM, CITI, UBS, BARC, GS, DB) with net exposure `$6.5M / $5.3M / $4.1M …`. Risk tab's Counterparty Exposure widget shows 10 names with net notionals `-$3.0M / $3.0M / -$2.9M …`. Different counts, different magnitudes, different signs.
8. **Liquidity classification is wrong.** `JPM` (one of the largest cash equities on earth) is flagged `ILLIQUID` with a 10-day horizon. `DE10Y` too. `LVaR Contrib` is shown in single dollars (`$0.7`, `$0`). And "Calculated 4/7/2026" — 50 days stale on a 2026-05-28 dashboard, with no staleness warning on the tile.
9. **EOD History `PV` column is `—` for every row.** Daily VaR/ES are recorded but PV is missing — you cannot reconcile EOD risk to portfolio value.
10. **Stress test result inconsistency.** Risk → "Stress Test Summary" says *"No stress test results yet. Run a stress test to see the summary."* Right next to it, `+100BPS_PARALLEL` shows `Δ PV -$1,589,994.06` as-of 2026-05-28 10:25. The headline says "no results"; the body has results.

### P1 — Cross-tab consistency

11. **Ticker strip vs Positions vs Risk** disagree on book size. Ticker `NAV $5.6B`. Positions tab "Firm Summary" $5.6B but earlier render returned $1.6B with -$647M unrealised. Positions tab "Live" tile says `Market Value $21.1M`. Risk tab `PV: $21.7M`. Are we showing firm or book? The page chrome doesn't say.
12. **Default scope is ambiguous.** Positions table shows 25 rows all in `balanced-income`, but the page banner says *"Firm Summary"*. Either the table is filtered (and we should say so loudly), or the summary is mislabelled.
13. **Currency Breakdown shows USD + EUR only.** GBP and JPY appear in book names and the currency selector, but the breakdown table omits them. A trader will assume "no GBP exposure" — wrong assumption.
14. **`VaR 1D 95% ↑ $0.00 (+0.0%)`** in the ticker strip while 5,135 live trades and a stream of TRADE_EVENT valuations are ticking. The delta-since-last should never be zero for an active book.
15. **VaR Trend chart Y-axis scaled `$0 → $3M`** with the actual VaR plotted around `$190K` — looks like a flatline. Auto-fit the axis to the data range or set sensible padding.
16. **Activity tab uses `2/21/2026, 2:00:01 PM`** (US date) while every other tab uses ISO (`2026-05-27`). Pick one.

### P2 — Workflow gaps

17. **No risk impact preview in `Place Order`.** Form has Instrument / Side / Quantity / Order Type / Arrival / Limit / TIF / Venue, then `Submit`. No live quote, no spread, no NBBO, no estimated notional, no "this order moves VaR by $X / Delta by Y". A trader will not put on size blind. **Add a "What-If" panel that runs the order through the same valuation path before submit.**
18. **No hedging workflow.** The system tells me where I'm exposed; it never helps me figure out what to do about it. A "suggest hedge" or "what trade reduces VaR by 50%" affordance is the single highest-leverage feature for actual traders.
19. **Limits table shows the limit, not the usage.** `FIRM NOTIONAL 800,000,000` with Intraday/Overnight columns `—`. The whole point of a limits screen is "how close am I to the wall?" Show current value + utilisation %.
20. **Alerts pile up forever.** 50 triggered, 0 acknowledged, 0 escalated, 0 resolved, with the same `derivatives-book VaR breach @ 1,372,142.47` repeating verbatim. Need (a) deduplication of repeat breaches, (b) batch select / acknowledge, (c) filtered views (only my books, only critical).
21. **`Trade Blotter Status` is `LIVE` for every row.** That is not a fill state. A real blotter distinguishes WORKING / FILLED / PARTIAL / CANCELLED / REJECTED, with `qty filled / qty open`. As-shown, I can't tell what's done from what's still in the market.
22. **No venue column on the blotter** despite a "Show Venue" filter chip.
23. **No P&L / markout column on the blotter.** TCA without per-trade slippage is half a screen.
24. **Reports tab has no history.** I can `Generate Report` but I can't see what was generated, by whom, when, or whether it's still running. Add a recent-reports list with status.
25. **Regulatory tab is empty by default.** "Click Calculate FRTB" — no last-calculated date, no most-recent submission, no SA-CCR / Initial Margin alongside.
26. **Counterparty PFE shown with no methodology** (Monte Carlo? 95%? horizon?), no confidence level, no time bucket. "Peak PFE $7.2M" is meaningless without the units of risk.
27. **`CP-DB Agreement Expired` badge** with no remediation CTA. If an ISDA is expired, a trader needs a one-click "open ticket" or "block new trades with this CP".
28. **Activity tab shows only `TRADE_BOOKED` events** but claims `1661 events` chain-verified. Where are RUN_PROMOTED / LIMIT_BREACH / RECONCILIATION / AMEND / CANCEL events? Either surface them or relabel "Trade history".

### P3 — Polish

29. **"Helpers" vs "Details" buttons on Positions** with no obvious distinction in function or hover hint.
30. **Demo mode banner is persistent and not session-dismissible** — it eats vertical real estate for the whole session.
31. **`Frozen` header pill is unlabelled.** Frozen what? Hover tooltip needed.
32. **`Component diversification –$0.01 (0.00 %)`** — collapse this to `~$0` so the column doesn't draw the eye.
33. **`VaR Contrib %` column can sum >100 %** because of negative diversification contributors — at least show the sum in a footer row so the discrepancy is explicit.
34. **Three identical valuation jobs at 10:38:20** in the jobs table (same VaR/ES/PV, same timestamp, different job IDs). Either dedupe or label "batch".
35. **EOD Promoted column shows `Status = Promoted` and `Promoted By = SEED` for every row** — fine for demo data, but expose the promoter user on real runs.

---

## Decisions applied

To make this plan loop-ready (`/loop /work-plan plans/ui-trader-review.md`), defaults below are pre-resolved. Override in a follow-up if you disagree.

- **TDD throughout.** Every fix-checkbox writes a failing test first (unit at the boundary of the bug, plus a higher-level acceptance/Playwright test where the bug is user-visible), then ships the minimal change to make it green. Run the full module suite on every change per the project testing philosophy.
- **Default fix branch:** create one PR per P0 item, one PR per logical P1 cluster. P2/P3 items can ride along on related P1 PRs unless they are pure-UI polish, in which case they batch.
- **No Allium drift.** If a spec under `specs/` describes the broken behaviour, change the spec first (`/tend`) before propagating to code (`/weed`). Risk-engine Greek attribution and counterparty PFE both look like spec-touching changes.
- **No new dependencies, no new top-level services.** Every fix lives inside an existing module unless the user explicitly relaxes that rule. P2 items 17 (`What-If preview on Place Order`) and 18 (`hedging workflow`) MAY require a new gateway endpoint — flag and ask before adding.
- **Out-of-scope items are documented at the bottom under `## Out of scope`**, no checkbox.
- **CI/CD approval:** none of the items below should touch CI/CD config. If a fix needs a workflow change, stop and ask.

---

## Plan

Ordered top-to-bottom by dependency. P0 first because every P1 cross-tab inconsistency may resolve once P0 calc engine bugs are fixed.

### P0 — Calc engine correctness

- [x] **Investigate per-instrument Greeks aggregation bug.** Trace where `Risk → Position Risk Breakdown` per-row Delta/Gamma/Vega come from — likely the risk-orchestrator → gateway projection that maps asset-class aggregates onto per-instrument rows. Write a failing test (`risk-engine` or `risk-orchestrator`) asserting `delta(AAPL) ≠ delta(JNJ)` for a fixture book of two cash-equity positions with different share counts, then fix.
  Acceptance: `./gradlew :risk-orchestrator:test --tests "*PerInstrumentGreeksTest"` (or equivalent risk-engine pytest) plus `./gradlew :gateway:acceptanceTest --tests "*PositionRiskBreakdownAcceptanceTest"` green.

- [x] **Populate per-instrument DV01 / Theta / Rho.** Same code path as above; add DV01 to the gateway response, plumb to UI column. Write a failing acceptance test asserting `DV01 > 0` for a Treasury fixture and `DV01 == 0` for a cash-equity fixture.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*PositionRiskGreeksAcceptanceTest"` green, and `cd ui && npx playwright test e2e/risk-greeks-shapes.spec.ts` green after extending the fixture to assert DV01 is rendered.

- [x] **Fix asset-class taxonomy for Treasuries.** Find the classifier that maps `UST-5Y/UST-10Y/PG` to `EQUITY/Cash Equity`. Reference-data or instrument-master miscoding is the likely culprit. Failing test: `ReferenceDataAcceptanceTest` asserting `UST-10Y.assetClass == GOVERNMENT_BOND` and `UST-10Y.instrumentType != CASH_EQUITY`. Then fix the upstream seed/classifier and re-derive everywhere it propagates.
  Acceptance: `./gradlew :reference-data-service:acceptanceTest --tests "*InstrumentTaxonomy*"` plus `./gradlew :position-service:acceptanceTest --tests "*PositionAssetClassAcceptanceTest"` green.

- [x] **Reconcile UST-* zero-market-value vs active trades.** After the taxonomy fix, write a failing position-service integration test that books a `UST-10Y` trade and asserts the resulting position has non-zero `marketValue` and appears under `assetClass = GOVERNMENT_BOND` in the gateway projection.
  Acceptance: `./gradlew :position-service:integrationTest --tests "*GovernmentBondPositionMaterializationIntegrationTest"` green.

- [x] **Reconcile the three Risk-tab VaR totals.** Decide canonical: header value = firm-total (or selected scope) VaR from the latest promoted run; "Sum of books" + diversification benefit must equal it; Factor Decomposition must use the same run/scope. Write a failing acceptance test that pulls all three from the gateway response and asserts they reconcile within rounding.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*RiskDashboardReconciliationAcceptanceTest"` green.

- [x] **Single source of truth for counterparty exposure.** Wire both the Risk tab widget and the Counterparty Risk tab to the same gateway endpoint. Failing test: gateway acceptance test that the two endpoints (or single endpoint with two projections) return the same name set and net exposures for a fixture book.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*CounterpartyExposureConsistencyAcceptanceTest"` green.

- [x] **Fix liquidity classification + remove single-dollar LVaR scale.** Add a regression test asserting `JPM.liquidity == HIGH_LIQUID` and that LVaR contribution is denominated to match the rest of the dashboard ($K or $M, not $). Surface a staleness warning when the `Calculated` timestamp is more than 1 day old.
  Acceptance: `./gradlew :risk-orchestrator:test --tests "*LiquidityClassificationTest"` plus a UI Vitest assertion on the LVaR formatter.

- [x] **Populate EOD History `PV` column.** Failing test: `EodHistoryAcceptanceTest` asserts `pv != null` on each promoted row.
  Acceptance: `./gradlew :risk-orchestrator:acceptanceTest --tests "*EodHistoryProjection*"` green plus `cd ui && npx playwright test e2e/eod-timeline.spec.ts` updated to assert PV cell content.

- [x] **Fix the stress test "no results yet" / has-results inconsistency.** Either show last-run summary when one exists, or hide the inline `Δ PV` widget when the headline says "no results". Failing test: Playwright assertion that the headline and the inline result agree.
  Acceptance: `cd ui && npx playwright test e2e/risk-stress-summary.spec.ts` green.

### P1 — Cross-tab data consistency

- [x] **Make page scope explicit on Positions.** Add a "Showing: book = balanced-income" badge near "Firm Summary", or relabel when filtered. Failing Playwright test asserts the badge reflects the active filter.
  Acceptance: `cd ui && npx playwright test e2e/positions-scope-banner.spec.ts` green.

- [x] **Expand Currency Breakdown to all currencies with non-zero exposure.** Failing acceptance test on the gateway summary endpoint asserting GBP/JPY rows when fixture positions include them.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*CurrencyBreakdownAllCurrenciesAcceptanceTest"` green.

- [x] **Fix the "VaR delta since last" being stuck at $0.00.** Failing test in risk-orchestrator: after two distinct valuation runs with different inputs, the surfaced delta is non-zero.
  Acceptance: `./gradlew :risk-orchestrator:test --tests "*VarDeltaSurfaceTest"` green.

- [x] **Auto-fit VaR trend chart Y-axis.** Vitest unit on the chart component; assert the computed Y-domain ≤ 1.5× max series value.
  Acceptance: `cd ui && npm run test -- VarTrendChart` green.

- [x] **Normalise date formats to ISO across all tabs.** Failing Playwright test scans every visible date string on Activity and asserts ISO-8601 shape.
  Acceptance: `cd ui && npx playwright test e2e/date-format-consistency.spec.ts` green.

### P2 — Workflow gaps

- [x] **Risk-impact preview on Place Order.** Spec change first (Allium under `specs/`), then a gateway endpoint that runs the candidate trade through the existing valuation path and returns Δ VaR, Δ Delta, Δ Notional, Δ counterparty exposure. UI Vitest + Playwright asserting the preview panel populates on form-blur.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*PreTradeRiskPreviewAcceptanceTest"` plus `cd ui && npx playwright test e2e/place-order-risk-preview.spec.ts` green.
  Approved 2026-05-29: user granted approval to add a new gateway route (`POST /api/v1/risk/pretrade-preview` or close equivalent) that runs the candidate trade through the existing valuation path and returns Δ VaR / Δ Delta / Δ Notional / Δ counterparty exposure. No new top-level service, no new dependency. Spec change in `specs/` first if the behaviour is spec-covered; otherwise straight to the gateway route + UI panel.

- [x] **Limits screen shows utilisation, not just limit.** For each limit row, populate the Intraday and Overnight cells with current value + utilisation %. Failing acceptance test on the limits endpoint.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*LimitsUtilisationAcceptanceTest"` green plus Playwright assertion on the rendered cells.

- [x] **Alert deduplication + batch acknowledge.** Suppression rules and a `Select all visible` / `Acknowledge selected` action. Failing acceptance tests on both the alerting service and UI.
  Acceptance: `./gradlew :notification-service:acceptanceTest --tests "*AlertDedupAcceptanceTest"` plus `cd ui && npx playwright test e2e/alerts-batch-ack.spec.ts` green.

- [x] **Trade blotter: real fill states + quantity-open column.** Map current `LIVE` to WORKING/FILLED/PARTIAL/CANCELLED/REJECTED with `qtyFilled/qtyOpen`. Failing acceptance test on the blotter endpoint, Playwright asserts mixed-status rows render correctly.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*TradeBlotterFillStateAcceptanceTest"` plus `cd ui && npx playwright test e2e/trade-blotter-status.spec.ts` green.

- [x] **Add venue column to blotter (it's already in the filter).** Failing Playwright test asserts the column header is present after enabling the "Show Venue" filter.
  Acceptance: `cd ui && npx playwright test e2e/trade-blotter-venue.spec.ts` green.

- [x] **Reports tab: recent reports list with status.** New panel listing the last N generated reports with timestamp, user, status (RUNNING/COMPLETE/FAILED), download link. Failing acceptance test on the reports endpoint.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*RecentReportsAcceptanceTest"` green plus Playwright.

- [x] **Regulatory tab default: show last calculation, not empty state.** Failing acceptance + Playwright.
  Acceptance: `./gradlew :regulatory-service:acceptanceTest --tests "*FrtbLastCalculationAcceptanceTest"` plus `cd ui && npx playwright test e2e/regulatory-last-calc.spec.ts` green.

- [x] **Counterparty PFE shows methodology, horizon, confidence.** Failing Playwright test asserts the tile labels include method (`MC_95_1Y` or similar).
  Acceptance: `cd ui && npx playwright test e2e/counterparty-pfe-methodology.spec.ts` green.

- [x] **CP-DB "Agreement Expired" CTA.** Add a "Block new trades / open ticket" action that hits an existing endpoint (or stub the click handler with a regulatory-service ticket creation). Failing Playwright test asserts the CTA is present on the expired row.
  Acceptance: `cd ui && npx playwright test e2e/counterparty-expired-cta.spec.ts` green.

- [x] **Activity tab: include non-`TRADE_BOOKED` events.** Failing acceptance test pulls events of types LIMIT_BREACH, RUN_PROMOTED, RECONCILIATION_BREAK and asserts they reach the gateway projection.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*ActivityFeedAllEventTypesAcceptanceTest"` green plus Playwright.

### P3 — Polish

- [x] **Tooltip on Helpers / Details / Frozen header chips.** Vitest unit asserts `title`/`aria-label` content.
  Acceptance: `cd ui && npm run test -- HeaderChips` green.

- [x] **Session-dismiss the demo banner.** Vitest unit + Playwright that asserts the dismissed state persists across reloads within the session.
  Acceptance: `cd ui && npx playwright test e2e/demo-banner-dismiss.spec.ts` green. (Banner renders only in demo mode; the spec runs under the demo config: `cd ui && VITE_DEMO_MODE=true npx playwright test --config playwright.demo.config.ts demo-banner-dismiss` — 4 passed.)

- [x] **Collapse rounding-noise rows (diversification ≈ $0).** Vitest unit on the formatter that returns `~$0` below a configurable epsilon.
  Acceptance: `cd ui && npm run test -- diversificationFormatter` green.

- [x] **VaR Contrib % footer row showing column sum.** Vitest on the component.
  Acceptance: `cd ui && npm run test -- VarContribTable` green.

- [x] **Dedupe identical valuation jobs in the table.** Failing acceptance test asserts jobs with identical `{ts, varValue, esValue, pvValue}` collapse to one row with a `(x3)` badge.
  Acceptance: `cd ui && npm run test -- ValuationJobsTable` green.

---

## Out of scope (track separately if/when needed)

- Real exchange/venue connectivity, FIX session management, market-data subscription tuning.
- Backtest of VaR model adequacy (regulatory backtesting cadence already exists under Regulatory).
- AI Copilot tone / answer quality — separate plan under `plans/ai-v2.md`.
- New asset classes (commodities futures, FX options) not currently in the demo data.
- Anything that requires changing CI/CD pipelines, adding new top-level services, or new external dependencies.

---

## Marcus's bottom line

The product has the right shape. The problem is the numbers. **A trader cannot use a system where per-position Greeks are wrong, asset classes are mis-tagged, three VaR totals disagree on the same page, and the alerts queue grows without bound.** Get the calc-engine bugs (P0 #1–#10) clean first — once those are right, most of the cross-tab inconsistencies will probably fall out for free. Then layer in the workflow features that turn a risk-monitoring screen into a risk-managing screen: pre-trade risk preview, limit utilisation, hedging suggestions, real fill states. That's the order I'd run the desk in. — Marcus
