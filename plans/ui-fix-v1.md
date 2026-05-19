# Kinetix UI Fix Plan v1

Driven by a fresh end-to-end audit of https://kinetixrisk.ai on 2026-05-19 plus
trader review. The dominant finding: synchronous risk computation
(`POST /api/v1/risk/var|stress|what-if|hedge-suggest|var/cross-book`) returns 500
across the board, so the Risk ticker strip, Firm Summary, WhatIf, Hedge,
Scenarios Run All, and the entire on-demand risk story all surface as $0.00 or
empty in a UI that otherwise renders correctly. A handful of targeted bugs
(false CRITICAL data-quality, garbled FRTB routing, stacked stale breach
banners) compound the trust hit.

Loop-ready for `/work-plan`. Each `- [ ]` is one independently committable
change with an `Acceptance:` command on the line immediately under it. Advance
end-to-end with `/loop /work-plan plans/ui-fix-v1.md`.

## Decisions applied

- **Ordering** follows the trader review: the false-CRITICAL data-quality bug
  (G1) leads because it poisons every operator's signal-to-noise; the
  synchronous-calc 500 cascade (B1/B2) is treated as a single incident and
  taken next; banner rollup (G3) and Reports (B3) follow; EOD promotion (B4),
  Regulatory tab completeness (M1), and the medium items close out the plan.
- **B1/B2 as one investigation.** B2 (firm aggregation = zeros) is a downstream
  symptom of B1 (cross-book VaR POST 500). They share the same gateway →
  risk-orchestrator HTTP path. Treat as a single fix; verify both recover
  together.
- **Suspected shared seam for B1/B2** is
  `gateway/.../client/HttpRiskServiceClient.kt` — it's the one client used by
  every failing POST route (VaRRoutes, CrossBookVaRRoutes, StressTestRoutes,
  WhatIfRoutes, HedgeRecommendationRoutes). The root cause may instead be in
  the risk-orchestrator itself — the diagnose step lands first so the fix step
  is informed.
- **TDD pair = one checkbox.** Per CLAUDE.md "Commit Practices" and
  `plans/demo-v2.md` precedent, the failing acceptance test and the production
  fix that turns it green LAND IN THE SAME COMMIT under a single checkbox.
  Splitting red→green into separate checkboxes trips `/work-plan` because the
  first checkbox's verification command would fail by design. Subagents must
  write the test first locally (red), then apply the minimal production change
  (green), then commit both together. CLAUDE.md "Bug fixes need a reproducing
  test before the fix" still applies — TDD discipline does not change, only
  the commit granularity.

  Already-shipped first half: commit `470768df` (failing
  `DataQualityRoutesAcceptanceTest`) was pushed under the old structure
  before this restructure. Checkbox 1.1 below is therefore the *fix half* of
  that pair — apply the production change and verify the test goes green.
- **No new dependencies, no new Kafka topics, no new tables, no new API
  contracts.** Every fix is contained to an existing module.
- **Demo seed touches** (M2 reconciliation/execution-cost, M3 SOD baseline,
  B4 EOD promotion) live inside `demo-orchestrator/` and reuse existing
  `DevDataSeeder` constants.
- **Visual fixes** (G3 banner rollup, the Reports error toast in M4) are
  covered by Vitest + a Playwright spec in `ui/e2e/`. Per CLAUDE.md every UI
  change needs both layers — no exceptions.
- **Lint before push.** `cd ui && npm run lint` runs as the final acceptance on
  every UI checkbox.

## CI/CD approval

No `.github/workflows/*` changes anticipated. Subagents touching Helm values
(`deploy/helm/kinetix/charts/demo-orchestrator/`) or
`deploy/docker-compose.services.yml` are deployment config, not CI pipelines —
proceed without an explicit gate. If a checkbox needs to touch an actual CI
pipeline file, STOP and flag.

## Out of scope

- New library dependencies in any module. If a checkbox tempts toward one,
  STOP and flag (CLAUDE.md guardrail).
- Rewriting `HttpRiskServiceClient` or the risk-orchestrator HTTP contract.
  We're fixing breakage, not redesigning.
- Workspace-views server-side persistence (M5). Confirmed `localStorage`-only
  is the current design intent; cite ADRs if reopened.
- Migrating any test to a different framework, or weakening assertion
  coverage. CLAUDE.md "Never delete, disable, or skip a test" applies.
- Cosmetic redesigns of working tabs (Counterparty Risk, Positions, Trade
  Blotter, System).

## Execution plan

### PR 1 — Stop poisoning the alert channel (G1)

- [x] 1.1 Apply the fix half of the G1 TDD pair. Edit
      `gateway/src/main/kotlin/com/kinetix/gateway/routes/DataQualityRoutes.kt:57`:
      rename `PortfolioListItemDto(val id: String)` to `(val bookId: String)`
      (or add `@SerialName("bookId")` if the gateway DTO surface needs to keep
      `id`). The failing acceptance test from commit `470768df`
      (`DataQualityRoutesAcceptanceTest`) must now go green.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*DataQualityRoutesAcceptance*"`
- [x] 1.2 Run a hot-rebuild against the live deploy and verify
      `curl https://api.kinetixrisk.ai/api/v1/data-quality/status` reports
      `Position Count` status `OK` with a `N portfolio(s) active` message,
      and the header `DataQualityIndicator` in the live UI no longer shows the
      CRITICAL dot. The Playwright spec
      `ui/e2e/data-quality-reconnect.spec.ts` continues to pass.
      Acceptance: `cd ui && npx playwright test data-quality-reconnect.spec.ts`

### PR 2 — Firm aggregation + cross-book VaR + 4xx-on-bad-payloads (B2 + restated B1)

Restated after diagnosis 2.1: single-book risk POSTs are healthy when called
with the UI's actual payload shape. The real bugs are (a) the firm hierarchy
aggregation returning zeros, (b) the cross-book VaR POST still 500-ing with
a valid payload, and (c) the gateway flattening every validation throwable
into a generic 500 instead of a 400 with a field name. The `[x]` 2.1
checkbox already captured this.

- [x] 2.1 Diagnose: see `## Diagnosis log` entry 2.1 below.
      Acceptance: `grep -q "^### 2.1 " plans/ui-fix-v1.md`
- [x] 2.2 TDD pair (test + fix in one commit): fix
      `GET /api/v1/risk/hierarchy/firm/firm` returning zeros. Add an
      acceptance test in `gateway/src/test/kotlin/com/kinetix/gateway/routes/HierarchyRiskRoutesAcceptanceTest.kt`
      (or extend the existing one) that POSTs cross-book VaR first to
      populate the cache (or seeds the cache directly via the test backend)
      and asserts the hierarchy/firm/firm response has `varValue != "0.00"`,
      `childCount > 0`, and a populated `topContributors` list. The fix
      most likely lives in `risk-orchestrator/` (the aggregation read path)
      or the gateway's `HierarchyRiskRoutes.kt` — diagnose during the work.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*HierarchyRisk*"`
- [x] 2.3 TDD pair (test + fix in one commit): fix
      `POST /api/v1/risk/var/cross-book` returning 500 with a valid UI-shape
      payload. Add an acceptance test in
      `gateway/src/test/kotlin/com/kinetix/gateway/routes/CrossBookVaRRoutesAcceptanceTest.kt`
      (or extend) that POSTs a multi-book cross-book request and asserts
      200 with the canonical `StressedCrossBookVaRResponse` shape. Fix
      whichever seam is breaking — gateway DTO, HttpRiskServiceClient call,
      or risk-orchestrator side.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*CrossBookVaR*"`
- [x] 2.4 TDD pair (test + fix in one commit): gateway global error
      handler returns 400 (not 500) when the request body fails to
      deserialize or fails a `require(…)` validation. Add an acceptance
      test that POSTs `/api/v1/risk/var/balanced-income` with
      `{"confidenceLevel": 0.95}` (numeric, wrong type) and asserts 400
      with a JSON body containing the offending field name. Fix lives in
      the gateway's status-pages plugin / global error handler
      (`gateway/.../Application.kt` or equivalent).
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*StatusPagesAcceptance*"`
- [x] 2.5 Live-deploy smoke: create `plans/scripts/check-risk-recovery.sh`
      (~25 lines) that curls each risk POST with the **correct UI-shape
      payload** and asserts 200 for `/risk/var/{book}`,
      `/risk/stress/{book}`, `/risk/what-if/{book}`, and
      `/risk/var/cross-book`; then asserts
      `GET /api/v1/risk/hierarchy/firm/firm` has `varValue != "0.00"` AND
      `childCount > 0`. Exit non-zero on any failure. The hedge-suggest
      endpoint is excluded because it's a pre-condition error (412 once
      patched), not a recovery check.
      Acceptance: `bash plans/scripts/check-risk-recovery.sh`

      Status (2026-05-19): script lands and the four risk-POST checks pass
      against the live deploy. The hierarchy check correctly reports the
      pre-2.2-binary state as a failure — it will turn green after the next
      `./deploy/redeploy.sh` rolls position-service forward to commit
      c9902266 (the `book_hierarchy` seed). The script's job is to *be* the
      durable smoke; the deploy refresh is a separate operational gate
      owned by the user.

- [x] 2.6 TDD pair (Playwright + targeted UI fixes in one commit): a
      Playwright spec in `ui/e2e/risk-ticker-strip.spec.ts` confirms
      `NAV`, `VAR 1D 95%`, `NET DELTA`, `NET VEGA` are all non-empty
      within 5s of load; a Playwright spec in `ui/e2e/book-summary-card.spec.ts`
      confirms the Firm Summary NAV is non-zero when the firm hierarchy is
      populated. Both specs assume the post-2.2 backend produces real
      data. Tweak only `BookSummaryCard.tsx` / `RiskTickerStrip.tsx` if a
      frontend gap shows up (e.g. they were hardcoding em-dashes when the
      hook returned a fresh zero value).
      Acceptance: `cd ui && npx playwright test risk-ticker-strip.spec.ts book-summary-card.spec.ts && cd ui && npm run lint`

### PR 3 — Stop showing three identical stale breach banners (G3)

- [x] 3.1 TDD pair (Vitest + impl in one commit): extend
      `ui/src/components/BreachBanner.test.tsx` with a case asserting that
      given 3 alerts of the same `severity` + `bookId` + matching message
      template within a 24h window, the component renders a single rollup
      banner with text like `"3 VaR breaches in the last 24h — latest
      $2,512,730 (derivatives-book)"` and a `View all` link that navigates
      to the Alerts tab. Then implement the dedupe in
      `ui/src/components/BreachBanner.tsx`: group by
      `(severity, bookId, ruleType)` over a 24h window, render one banner
      per group. Use `useMemo`; pass through the existing `onDismiss` /
      `onOpenHedgePanel` props unchanged. Existing BreachBanner tests must
      stay green.
      Acceptance: `cd ui && npm run test -- BreachBanner`
- [x] 3.2 Playwright spec `ui/e2e/breach-banner-rollup.spec.ts` that mocks
      three matching VaR breaches via `page.route` (pattern from
      `ui/e2e/fixtures.ts`) and asserts exactly one banner with a count
      badge renders.
      Acceptance: `cd ui && npx playwright test breach-banner-rollup.spec.ts && cd ui && npm run lint`

### PR 4 — Reports generation 500 (B3 + M4)

- [ ] 4.1 Diagnose: capture the live `POST /api/v1/reports/generate` 500
      from gateway + regulatory-service logs (it's the report-service /
      regulatory-service producing the upstream_error). Append a dated entry
      to `## Diagnosis log` with the root-cause line.
      Acceptance: `grep -q "^### 4.1 " plans/ui-fix-v1.md`
- [ ] 4.2 TDD pair (test + fix in one commit): add
      `gateway/.../routes/ReportsGenerateAcceptanceTest.kt` POSTing
      `/api/v1/reports/generate` with `{templateId:"tpl-risk-summary",
      bookId:"balanced-income"}` against a fake upstream report-service,
      asserting 200 with the canonical `ReportResponse` shape. Then apply
      the production fix identified in 4.1 (most likely a contract /
      serialization bug; if it lives in regulatory-service rather than
      gateway, fix it there). Existing reports tests stay green.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*Reports*"`
- [ ] 4.3 TDD pair (Vitest + Playwright + impl in one commit): wire a
      visible error toast in `ui/src/components/ReportsTab.tsx` when
      `POST /api/v1/reports/generate` returns non-2xx (M4). Vitest covering
      the toast renders on 500 and shows the upstream `message` field.
      Playwright spec `ui/e2e/reports.spec.ts` mocks a 500 and asserts the
      toast appears.
      Acceptance: `cd ui && npm run test -- ReportsTab && cd ui && npx playwright test reports.spec.ts && cd ui && npm run lint`

### PR 5 — EOD promotion produces a populated timeline (B4)

- [ ] 5.1 TDD pair (integration test + impl in one commit): add a
      `demo-orchestrator/` integration test that boots demo-orchestrator +
      a Postgres Testcontainer, advances the simulated trading-day clock
      past the close, and asserts an EOD job is promoted via the
      `OfficialEodPromotedEvent` Kafka event AND that
      `GET /api/v1/risk/eod-timeline/balanced-income?from=…&to=…` returns at
      least one entry. Then wire the EOD-promotion scheduler (cite
      `plans/demo-v2.md` Decision: "EOD observation — Kafka topic
      `risk.official-eod`, event `OfficialEodPromotedEvent`"). Schedule runs
      daily at the configured close time; idempotent on re-run within the
      same simulated day.
      Acceptance: `./gradlew :demo-orchestrator:integrationTest --tests "*EodPromotion*"`
- [ ] 5.2 Smoke: create `plans/scripts/check-eod-recovery.sh` that curls
      `GET /api/v1/risk/eod-timeline/balanced-income?from=$(date -d "30 days
      ago" +%F)&to=$(date +%F)` and asserts the JSON `.entries` array
      length is greater than zero.
      Acceptance: `bash plans/scripts/check-eod-recovery.sh`

### PR 6 — Regulatory tab UI completeness (M1)

- [x] 6.1 TDD pair (Vitest + impl in one commit): extend
      `ui/src/components/RegulatoryTab.test.tsx` to assert that after a
      successful FRTB calc the component renders: a SBM-charges-by-risk-class
      table (GIRR, CSR_NON_SEC, EQUITY, FX, COMMODITY) with delta/vega/
      curvature/total columns; a totals strip (Total SBM, Net DRC, Total
      RRAO, Total Capital Charge); a "Calculated at <timestamp>" stamp.
      Then build the missing table + totals block in
      `ui/src/components/RegulatoryTab.tsx`. Reuse the existing
      `Calculate FRTB` / `Download CSV` / `Download XBRL` buttons unchanged.
      Acceptance: `cd ui && npm run test -- RegulatoryTab`
- [x] 6.2 Playwright spec `ui/e2e/regulatory-tab.spec.ts` exercising the
      full flow: click Calculate FRTB → wait for table → assert the EQUITY
      row contains both delta and total values formatted with thousands
      separators → click Download CSV and verify a file download starts.
      Acceptance: `cd ui && npx playwright test regulatory-tab.spec.ts && cd ui && npm run lint`

### PR 7 — Trades blotter: Reconciliation + Execution Cost demo data (M2)

- [ ] 7.1 TDD pair (integration test + impl in one commit): add a
      `demo-orchestrator/` integration test asserting that after the
      simulated trading day runs,
      `GET /api/v1/trades/reconciliation/balanced-income` returns at least
      one reconciliation row AND
      `GET /api/v1/trades/execution-cost/balanced-income?from=…&to=…`
      returns at least one row. Then extend the demo-orchestrator's trade
      simulator to emit a small stream of reconciliation breaks (~5% of
      trades) and per-trade execution-cost samples. Reuse `DevDataSeeder`
      instrument/book constants per `plans/demo-v2.md`. No new tables, no
      new API contracts.
      Acceptance: `./gradlew :demo-orchestrator:integrationTest --tests "*ReconExecution*"`
- [ ] 7.2 Re-run the Playwright spec `trades-blotter.spec.ts` and add
      `ui/e2e/trades-recon-execution-cost.spec.ts` asserting both subtabs
      render non-empty grids.
      Acceptance: `cd ui && npx playwright test trades-blotter.spec.ts trades-recon-execution-cost.spec.ts && cd ui && npm run lint`

### PR 8 — P&L SOD baseline auto-seeded in demo (M3)

- [ ] 8.1 TDD pair (integration test + impl in one commit): add a
      `demo-orchestrator/` integration test confirming a SOD baseline is
      captured at the configured trading-day open and that the P&L
      Waterfall endpoint returns non-zero Gamma/Vega/Theta/Rho components
      for a book with mixed asset classes. Then wire the SOD-baseline
      capture in demo-orchestrator's day-open hook; idempotent within the
      same simulated day.
      Acceptance: `./gradlew :demo-orchestrator:integrationTest --tests "*SodBaseline*"`
- [ ] 8.2 Playwright spec `ui/e2e/pnl-sod-baseline.spec.ts` asserts that on
      a fresh page load, the "No SOD baseline for today" callout is absent
      and the waterfall shows non-zero values across at least three of
      (Delta, Gamma, Vega, Theta, Rho).
      Acceptance: `cd ui && npx playwright test pnl-sod-baseline.spec.ts && cd ui && npm run lint`

### PR 9 — Remove the broken /regulatory/frtb/calculate route (G2)

- [x] 9.1 TDD pair (test + fix in one commit): add a case to
      `RegulatoryFrtbRoutesAcceptanceTest` asserting
      `POST /api/v1/regulatory/frtb/calculate` returns 404 (route removed)
      or 400 (route refuses the literal string "calculate" as a bookId) —
      pick whichever matches the cleanest implementation. Then apply the
      route fix: remove the segment that captured `calculate` as `{bookId}`,
      OR add a 400-guard rejecting the literal "calculate" / a small list
      of reserved path words. Existing
      `POST /api/v1/regulatory/frtb/{realBookId}` tests stay green.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*RegulatoryFrtbRoutesAcceptance*"`

### PR 10 — Intraday VaR default range + remaining polish (M6, minors)

- [ ] 10.1 TDD pair (Vitest + impl in one commit): add a test to
      `ui/src/hooks/useIntradayVaRTimeline.test.ts` (or equivalent)
      asserting the hook always passes `from` and `to` to the gateway,
      even when the user has not yet picked a range. Default =
      `(now - 24h, now)` in FROZEN replay mode, `(SOD today, now)` in LIVE.
      Then patch the hook + the Risk > Intraday sub-tab to always send both
      query params.
      Acceptance: `cd ui && npm run test -- useIntradayVaRTimeline && cd ui && npm run lint`
- [x] 10.2 TDD pair (Vitest + impl in one commit): hide the IDX-SPX
      2067h-stale warning in `DataQualityIndicator` when
      `tapeReplay.status === "FROZEN"`. Vitest covering the conditional
      renders.
      Acceptance: `cd ui && npm run test -- DataQualityIndicator && cd ui && npm run lint`
- [ ] 10.3 Audit pass: re-run the original audit probe
      (checked in as `plans/scripts/audit-live-ui.mjs`) against the live
      deploy and confirm ZERO console errors, ZERO `api.kinetixrisk.ai`
      4xx/5xx responses, non-empty ticker strip, populated Firm Summary,
      single rollup breach banner, Reports generate succeeds end-to-end,
      EOD timeline has entries, Regulatory tab renders the SBM table.
      Acceptance: `node plans/scripts/audit-live-ui.mjs && grep -q '"consoleErrors": \[\]' /tmp/kinetix-audit/report.json`

## Diagnosis log

### 2.1 (2026-05-19) — "B1" was a payload-shape misdiagnosis

The original audit reported "every on-demand risk POST returns 500" by curling
with `{"calculationType":"PARAMETRIC","confidenceLevel":0.95,"timeHorizonDays":1}`
— numeric `confidenceLevel`, numeric `timeHorizonDays`. The gateway's
`VaRCalculationRequest` (see
`gateway/.../dtos/VaRCalculationRequest.kt`) expects
`confidenceLevel: String?` constrained to `{CL_95, CL_975, CL_99}` and
`timeHorizonDays: String?` (parsed via `.toInt()`). kotlinx.serialization
rejects the numeric-into-String mismatch and the global error handler
flattens any throwable into a generic 500 `internal_error`.

Re-curl with the UI's actual shape:

  curl -X POST .../api/v1/risk/var/balanced-income \
    -d '{"calculationType":"PARAMETRIC","confidenceLevel":"CL_95","timeHorizonDays":"1","numSimulations":"10000"}'
  → HTTP 200, full VaRResponse (varValue $148k, ES $186k, Greeks populated, …)

Same picture for `/risk/stress/{book}` (returns 200 with GFC_2008 stress
result) and `/risk/what-if/{book}` (returns 200 with base/hypothetical VaR).

So the synchronous single-book risk path is **healthy**. The UI calls these
endpoints with the correct shape and they work. The audit's "broken" finding
came from MY curl payloads, not the UI's.

What IS still broken under the correct payload shape:
- `POST /api/v1/risk/var/cross-book` — still 500 `internal_error` with the
  UI's payload shape. **Real bug.** Probably a separate code path in
  `CrossBookVaRRoutes.kt` / `HttpRiskServiceClient.kt`.
- `POST /api/v1/risk/hedge-suggest/{book}` — returns 500 `upstream_error`
  with descriptive message `"No VaR result available for book … Run a VaR
  calculation first."` when no VaR is cached for the book. This is a
  precondition, not a bug — but the status code should be 412 / 409, not 500.
- `GET /api/v1/risk/hierarchy/firm/firm` — returns 200 but
  `varValue: "0.00"`, `childCount: 0`. **B2 is independent of B1** and
  remains the dominant visible problem ($0.00 in the ticker strip + Firm
  Summary).
- `POST /api/v1/reports/generate` — still 500 `upstream_error: "Report
  generation failed"` with the UI's shape. **Real bug** (B3).

Secondary finding: the gateway's global error handler converts EVERY
deserialization / validation throwable into an opaque
`{"error":"internal_error","message":"An unexpected error occurred"}` 500.
This is what caused the misdiagnosis. A client sending a malformed payload
should get 400 with the specific field name and constraint — this would
have made the original audit trivial. Tracked as a new checkbox in the
restructured PR 2.

### 4.1 (2026-05-19) — Reports 500 root cause: ungraceful SQL exceptions

The gateway `POST /api/v1/reports/generate` 500 is a flat passthrough of an
upstream 500 from risk-orchestrator
(`risk-orchestrator/.../routes/ReportRoutes.kt:68-71`) — the route catches
every `Exception` from `reportService.generateReport(...)` and returns
`{"error":"internal_error","message":"Report generation failed"}`. The
gateway client (`HttpRiskServiceClient.generateReport`) then re-wraps that
as `UpstreamErrorException`, which the gateway surfaces as
`{"error":"upstream_error","message":"Report generation failed"}`. So the
real failure is in the SQL layer inside `JdbcReportQueryExecutor`.

Differential probing against the live deploy isolates two distinct SQL
faults — only `tpl-pnl-attribution` works:

  POST .../api/v1/reports/generate  body={templateId:tpl-pnl-attribution, …}
  → HTTP 200, 251 rows of pnl_attributions data

  POST .../api/v1/reports/generate  body={templateId:tpl-risk-summary, …}
  → HTTP 500 upstream_error  ("Report generation failed")

  POST .../api/v1/reports/generate  body={templateId:tpl-stress-summary, …}
  → HTTP 500 upstream_error  ("Report generation failed")

  POST .../api/v1/reports/generate  body={templateId:tpl-risk-summary, bookId:BOGUS}
  → HTTP 500 upstream_error  (same — the failure is below the bookId filter)

Two distinct causes, both flattened into the same opaque 500:

1. `tpl-risk-summary` queries the `risk_positions_flat` materialised view
   (`V55__create_risk_positions_flat_view.sql:69` — `WITH NO DATA;`). The
   refresher runs only after EOD promotion
   (`ScheduledMatViewRefreshJob.kt:16-22`), and EOD promotion is itself
   broken (B4 in this plan). PostgreSQL throws
   `ERROR: materialized view "risk_positions_flat" has not been populated`
   (SQLState `55000` — `object_not_in_prerequisite_state`) on every
   SELECT until the first refresh lands. The executor lets the
   `SQLException` propagate, the route flattens it to 500.

2. `tpl-stress-summary` queries `stress_test_results`, which the executor
   comment explicitly flags
   (`JdbcReportQueryExecutor.kt:43-46`): *"stress_test_results lives in
   the regulatory-service database, not risk."* The executor still binds
   to `RiskDatabaseFactory.dataSource` (`Application.kt:764`), so the
   query hits the wrong database and PostgreSQL throws
   `ERROR: relation "stress_test_results" does not exist` (SQLState
   `42P01` — `undefined_table`). Same flatten-to-500 path.

The minimal fix is to make `JdbcReportQueryExecutor` treat these two
known-degraded states as "0 rows" rather than letting the SQL exception
poison the whole endpoint: an unpopulated materialised view IS empty by
definition, and a missing cross-database table is "no data here for this
report on this deploy". Catch SQLState `55000` and `42P01`, log at WARN,
return an empty `JsonArray`. The route then returns a normal 200
`ReportOutput` with `rowCount: 0`, which the UI already renders as the
expected "0 rows" empty-report state.
