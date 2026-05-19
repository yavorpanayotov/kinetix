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
- [ ] 1.2 Run a hot-rebuild against the live deploy and verify
      `curl https://api.kinetixrisk.ai/api/v1/data-quality/status` reports
      `Position Count` status `OK` with a `N portfolio(s) active` message,
      and the header `DataQualityIndicator` in the live UI no longer shows the
      CRITICAL dot. The Playwright spec
      `ui/e2e/data-quality-reconnect.spec.ts` continues to pass.
      Acceptance: `cd ui && npx playwright test data-quality-reconnect.spec.ts`

### PR 2 — Bring synchronous risk computation back from the dead (B1/B2)

- [ ] 2.1 Diagnose: capture the live gateway error by curling
      `POST /api/v1/risk/var/balanced-income` against the running deploy and
      tailing `docker logs kinetix-gateway -f` (or
      `docker logs kinetix-risk-orchestrator -f` if the gateway is just
      forwarding an upstream error). Capture the stack trace + the upstream
      response from `HttpRiskServiceClient`. Append a dated entry to the
      `## Diagnosis log` heading at the bottom of this plan with one
      paragraph + the key log line(s).
      Acceptance: `grep -q "^### 2.1 " plans/ui-fix-v1.md`
- [ ] 2.2 TDD pair (test + fix in one commit): add
      `gateway/src/test/kotlin/com/kinetix/gateway/routes/VaRRoutesPostAcceptanceTest.kt`
      that POSTs `/api/v1/risk/var/balanced-income` with a valid
      `VaRRequest` against a Testcontainers-backed gateway + an in-JVM Netty
      fake of the upstream HTTP risk-orchestrator (per CLAUDE.md
      acceptance-test conventions — the fake echoes a canonical
      `VaRResponse` so the gateway-side serialisation is exercised, not the
      compute). Then apply the production fix identified in 2.1 — most
      likely in `gateway/.../client/HttpRiskServiceClient.kt`. Commit
      together. Both the new test and the rest of the gateway acceptance
      suite must pass.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*VaRRoutesPostAcceptance*"`
- [ ] 2.3 Regression: every other risk POST route (CrossBookVaRRoutes,
      StressTestRoutes, WhatIfRoutes, HedgeRecommendationRoutes) must remain
      green. If the fix in 2.2 was localised in `HttpRiskServiceClient` they
      all recover for free; if not, extend the fix.
      Acceptance: `./gradlew :gateway:acceptanceTest --tests "*VaRRoutes* *CrossBookVaR* *StressTest* *WhatIf* *HedgeRecommendation*"`
- [ ] 2.4 Live-deploy smoke: create `plans/scripts/check-risk-recovery.sh`
      (~20 lines) that curls
      `POST /api/v1/risk/var/balanced-income`,
      `POST /api/v1/risk/stress/balanced-income`,
      `POST /api/v1/risk/what-if/balanced-income`,
      `POST /api/v1/risk/hedge-suggest/balanced-income`, and
      `POST /api/v1/risk/var/cross-book` — every one must return 200. The
      script must then curl `GET /api/v1/risk/hierarchy/firm/firm` and assert
      `varValue != "0.00"` AND `childCount > 0`. Exit non-zero on any
      failure.
      Acceptance: `bash plans/scripts/check-risk-recovery.sh`
- [ ] 2.5 TDD pair (test + impl in one commit): two Playwright specs in
      `ui/e2e/`: one for `BookSummaryCard` on the Positions tab confirming
      non-zero NAV when the firm hierarchy is populated; one for the global
      `RiskTickerStrip` confirming `NAV`, `VAR 1D 95%`, `NET DELTA`,
      `NET VEGA` are all non-empty within 5s of load. The specs assume the
      backend now returns real data (post-2.2 fix). Adjust UI components
      only if the specs reveal a frontend bug (e.g. missing default
      formatting).
      Acceptance: `cd ui && npx playwright test risk-ticker-strip.spec.ts book-summary-card.spec.ts && cd ui && npm run lint`

### PR 3 — Stop showing three identical stale breach banners (G3)

- [ ] 3.1 TDD pair (Vitest + impl in one commit): extend
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
- [ ] 3.2 Playwright spec `ui/e2e/breach-banner-rollup.spec.ts` that mocks
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

- [ ] 6.1 TDD pair (Vitest + impl in one commit): extend
      `ui/src/components/RegulatoryTab.test.tsx` to assert that after a
      successful FRTB calc the component renders: a SBM-charges-by-risk-class
      table (GIRR, CSR_NON_SEC, EQUITY, FX, COMMODITY) with delta/vega/
      curvature/total columns; a totals strip (Total SBM, Net DRC, Total
      RRAO, Total Capital Charge); a "Calculated at <timestamp>" stamp.
      Then build the missing table + totals block in
      `ui/src/components/RegulatoryTab.tsx`. Reuse the existing
      `Calculate FRTB` / `Download CSV` / `Download XBRL` buttons unchanged.
      Acceptance: `cd ui && npm run test -- RegulatoryTab`
- [ ] 6.2 Playwright spec `ui/e2e/regulatory-tab.spec.ts` exercising the
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

- [ ] 9.1 TDD pair (test + fix in one commit): add a case to
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
- [ ] 10.2 TDD pair (Vitest + impl in one commit): hide the IDX-SPX
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

_(Subagents append captured stack traces and root-cause findings here as
checkboxes 2.2 and 4.2 execute. Keep entries short — one paragraph + the key
log line.)_
