# Demo Audit Issues — Round 1

Captured from a full UI audit of `kinetixrisk.ai` on 2026-05-26 (artifacts: `/tmp/kinetix-audit/`).
The site loads with no JS errors and every service reports READY, but the **VaR pipeline
is not bootstrapped after demo seed**, leaving the firm KPI bar, P&L tab, Risk tab, EOD,
and Reports either blank or showing "no data" copy. Five gateway endpoints also return
HTTP 500 (instead of 404) when their underlying data is absent — a defensive-coding bug
that will keep firing in any sparse environment.

This plan is loop-ready for `/work-plan`. Each `- [ ]` checkbox is one independently
committable change, ordered top-to-bottom by dependency, with an `Acceptance:` command
on the line directly after it. Advance end-to-end with `/loop /work-plan docs/plans/issues-1.md`.

The plan has been reviewed by trader, architect, qa, ux-designer, and data-analyst
agents; their findings have been integrated below. The most important integrations:
(a) the architect's correction that `Application.kt:170` already reflects upstream status
codes, so a diagnostic checkbox precedes Phase 1; (b) SOD baseline bootstrap added to
Phase 3 to unblock P&L attribution; (c) a new Phase 2.5 introduces graceful UX during the
~5s bootstrap window so the demo never shows `$0.00` while warming up.

## Decisions applied

- **Sequencing.** Phase 0 = diagnostic (confirm the actual exception path before fixing).
  Phase 1 = the five gateway 500→404/503 fixes. Phase 2 = Activity-tab auth race.
  Phase 2.5 = UX safety net for the bootstrap warm-up window. Phase 3 = demo VaR + SOD
  bootstrap (the user-visible payoff). Phase 4 = production verification against the
  live demo.
- **Bootstrap design.** New `DemoVaRBootstrapJob` in `demo-orchestrator/.../schedule/`,
  following the existing one-job-per-file pattern (alongside `EodPromotionJob`,
  `SimulatedTraderJob`). **Not** reusing `EodPromotionJob.runOnce()` — EOD promotion
  carries "official close" semantics that should not fire on every boot.
  `SodBaselineCaptureJob.runOnce()` is invoked alongside, in the same orchestration class,
  because the P&L Attribution report requires an SOD baseline (per data-analyst
  finding; route returns 412 Precondition Failed without it).
- **Bootstrap parameters.** VaR request body defaults: `confidenceLevel=0.95`,
  `horizonDays=10`, `method=PARAMETRIC`, `valuationDate=today`. These match the values
  the firm KPI bar reads in the UI (`VAR 1D 95%` cell). Overridable via env vars
  `DEMO_BOOTSTRAP_VAR_CONFIDENCE` / `DEMO_BOOTSTRAP_VAR_HORIZON`.
- **Books to bootstrap.** Reuse `DevDataSeeder.BOOK_HIERARCHY_MAPPINGS` so the source of
  truth stays in one place: `balanced-income, derivatives-book, emerging-markets,
  equity-growth, fixed-income, macro-hedge, multi-asset, tech-momentum` (8 books).
- **Currency.** All positions and aggregates assumed USD. `portfolio_risk.py` does no FX
  conversion. Documented here so a future contributor doesn't add multi-currency
  positions and silently break the aggregate.
- **Bootstrap result shape.** `BootstrapResult(successCount: Int, failureCount: Int,
  failedBooks: List<String>, durationMillis: Long)`. Locks the contract so tests don't
  invent an incompatible shape.
- **Idempotency.** `runOnce()` is safe to call multiple times — each call posts fresh
  VaR; the risk-orchestrator upserts in `daily_risk_snapshots`. Test pins this.
- **Retry policy.** On `ConnectException` or HTTP 5xx, retry per book with exponential
  backoff (3 attempts, 500ms / 1s / 2s). Handles risk-orchestrator coming up slowly
  in docker-compose. Empty-positions failures from risk-engine are not retried (the
  book truly has no positions — log and continue).
- **Readiness signal.** Bootstrap exposes `/demo/bootstrap-status` returning
  `{state: NOT_STARTED|IN_PROGRESS|READY|FAILED, successCount, failureCount}`. UI banner
  and audit script both poll it. No new database table — in-memory state.
- **Fix-layer for gateway 500s.** Decided after Phase 0 diagnostic. Default assumption
  (subject to override by Phase 0 findings): make the affected `HttpRiskServiceClient`
  methods return nullable + map upstream-404 → `null`, and let routes respond 404 on
  null. This is one fix per route instead of one try/catch per route, and matches the
  pattern in `getLatestVaR` (line 81-91). Routes only need a try/catch if Phase 0
  proves the exception originates outside the client call.
- **Firm aggregate via side-effect.** `GET /api/v1/risk/var/cross-book/firm` reads from
  the hierarchy aggregator (`HierarchyRiskService.aggregateHierarchy`) which composes
  per-book VaR. The plan asserts at 3.4 that this side-effect works; if it fails, a
  follow-up checkbox triggers an explicit `POST /api/v1/risk/var/cross-book`.
- **TDD.** Each commit lands test(s) + minimal implementation together so every commit
  builds green (CLAUDE.md "Commit Practices").
- **No new dependencies, no schema or contract changes.** No new Kafka topics, DB tables,
  or API shapes other than the readiness endpoint (a trivial GET, demo-orchestrator-local).

## CI/CD approval

No CI/CD pipeline files are touched by this plan. If a subagent discovers it must touch
`.github/workflows/*` or similar, STOP and flag (per `/work-plan` guardrails).

## Out of scope

- Greeks / intraday delta / gamma / DV01 bootstrap on the Risk tab. Those flow through
  the same risk-engine call as VaR (`calculateVaR` returns a `RiskResult` that includes
  Greek aggregates), so they should populate as a side-effect of this plan. If they
  don't, that's a separate issue (issues-2).
- Synthetic prior-day EOD snapshot so the EOD History tab isn't blank on day 1.
  Recommended by data-analyst — deferred to issues-2 to keep this plan scoped.
- Full empty-state polish across all tabs (issues-2).
- VaR freshness/timestamp display in the UI (trader requested — issues-2).
- Refactoring `HttpRiskServiceClient`'s `UpstreamErrorException` taxonomy (architect
  noted the layering is questionable; deferred until a broader cleanup).
- Backfilling historical EOD rows.
- New VaR scheduling cadences or surprise market events.

## Verification harness

A standalone Node + Playwright script lives under `scripts/demo-audit/` (committed in 4.1).
It hits the deployed demo, walks every tab, and emits a JSON report. Used by Phase 4.

---

## Phase 0 — Diagnostic

- [ ] **0.1 Pin the actual exception path for each 500-returning endpoint.** Run a focused integration test that points each of the 5 routes at a stub upstream returning HTTP 404 and HTTP 500, and capture the exception that hits Ktor's exception pipeline. Write findings to `docs/audits/2026-05-26-diagnostic-500.md`. Outcome determines whether the fix in Phase 1 is client-level (return-type → nullable) or route-level (try/catch). The Phase 1 acceptance tests are agnostic to which.
  Acceptance: `./gradlew :gateway:test --tests "*UpstreamExceptionPathDiagnosticTest"`

## Phase 1 — Gateway: 500 → 404/503 null-safety (5 endpoints)

Each task ends with a route file that returns 404 when upstream has no data and 503 when
upstream is unavailable. Tests cover: (a) happy path; (b) upstream-404 → gateway-404;
(c) upstream-500 → gateway-503; (d) **regression guard** — upstream-404 must NOT produce
gateway-500; (e) malformed `bookId` (empty / whitespace) → gateway-400 (not 500).

- [ ] **1.1 `GET /api/v1/risk/krd/{bookId}` — null-safe** (`gateway/.../routes/KeyRateDurationRoutes.kt:9-19`, possibly also `HttpRiskServiceClient.getKeyRateDurations`). Add `KeyRateDurationRoutesAcceptanceTest` with the five test cases above.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*KeyRateDurationRoutesAcceptanceTest"`

- [ ] **1.2 `GET /api/v1/books/{bookId}/factor-risk/latest` — null-safe** (`gateway/.../routes/FactorRiskRoutes.kt:10-19`). Same pattern as 1.1.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*FactorRiskRoutesAcceptanceTest"`

- [ ] **1.3 `GET /api/v1/books/{bookId}/margin` — null-safe** (`gateway/.../routes/MarginRoutes.kt:27-46`). Existing null-check on the result preserved; new behaviour adds the missing exception-to-status mapping.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*MarginRoutesAcceptanceTest"`

- [ ] **1.4 `GET /api/v1/risk/jobs/{bookId}/chart` — null-safe** (`gateway/.../routes/JobHistoryRoutes.kt:21-59`). Note: `getChartData` currently returns non-nullable. The likely fix per architect feedback is to change its return type to nullable in the client (one-line client change + route conditional). Also add a test for `from > to` → gateway-400.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*JobHistoryRoutesAcceptanceTest"`

- [ ] **1.5 `GET /api/v1/risk/pnl-attribution/{bookId}` — null-safe** (`gateway/.../routes/SodSnapshotRoutes.kt:67-86`). Same pattern as 1.1. Existing null-check on line 81 preserved.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*SodSnapshotRoutesAcceptanceTest"`

- [ ] **1.6 Targeted gateway smoke** — run only the five new test classes together to confirm no cross-contamination.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*KeyRateDuration*" --tests "*FactorRisk*" --tests "*Margin*" --tests "*JobHistory*" --tests "*SodSnapshot*"`

## Phase 2 — UI: Activity tab auth race

- [ ] **2.1 Gate `AuditLogPanel` fetches on auth readiness** (`ui/src/components/AuditLogPanel.tsx:115-144`). Pull `useAuth()` and short-circuit both `useEffect`s with `if (auth.initialising) return`. Add `auth.initialising` to each effect's dependency array. Vitest cases: (a) does not call fetch while initialising; (b) **transition test** — calls fetch exactly once when auth flips from initialising to ready. Also: change the user-visible copy "Verifying chain…" → "Loading activity log…" (`AuditLogPanel.tsx`, search exact string) — Vitest asserts the new copy is present and the old copy is absent.
  Acceptance: `cd ui && npm run test -- AuditLogPanel`

- [ ] **2.2 Playwright regression for Activity tab** — new spec `ui/e2e/activity-tab.spec.ts`. Use `waitForSelector('[data-testid="audit-event-row"]')` (not a wall-clock 5s timeout) to assert rows render after navigation. The test must not depend on a specific count, only on presence.
  Acceptance: `cd ui && npx playwright test e2e/activity-tab.spec.ts`

## Phase 2.5 — UI: bootstrap warm-up safety net

While `DemoVaRBootstrapJob` is running on first deployment, the UI will receive 404s for
~5s. This phase ensures the demo never shows misleading `$0.00`s during that window.

- [ ] **2.5.1 Firm KPI bar renders `—` on missing data, never `$0.00`** (`ui/src/components/FirmKpiBar.tsx` or wherever the cells are defined — grep for `NAV $`). When the firm aggregate endpoint returns 404 or a null/undefined value, render `—` with a `title="Calculating…"` tooltip. Vitest test: when data prop is null, cells show `—` and never `$0.00`.
  Acceptance: `cd ui && npm run test -- FirmKpiBar`

- [ ] **2.5.2 "Initialising demo data" banner** — small dismissible banner above the tab bar that appears when `GET /demo/bootstrap-status` returns `state != READY`, polls every 3s, auto-dismisses on `READY`. Vitest tests for: render-on-IN_PROGRESS, hide-on-READY, manual-dismiss. Playwright spec asserts the banner appears on first load and disappears within 15s.
  Acceptance: `cd ui && npm run test -- BootstrapBanner && cd ui && npx playwright test e2e/bootstrap-banner.spec.ts`

## Phase 3 — Demo VaR + SOD bootstrap

- [ ] **3.1 New `DemoVaRBootstrapJob` (TDD)** — `demo-orchestrator/src/main/kotlin/com/kinetix/demo/schedule/DemoVaRBootstrapJob.kt`. Single `runOnce(): BootstrapResult` that iterates `DevDataSeeder.BOOK_HIERARCHY_MAPPINGS.keys` and calls `riskOrchestratorClient.calculateVaR(bookId, BOOTSTRAP_PARAMS)` per book. Retry on `ConnectException` / 5xx with exponential backoff (3 attempts, 500ms/1s/2s). No retry on empty-positions failures (terminal). Per-book failures captured in `BootstrapResult.failedBooks` — one bad book never blocks the rest. Tests (Kotest FunSpec, MockK):
  - (a) calls VaR for every seeded book exactly once on first run
  - (b) **idempotency**: a second `runOnce()` invocation still completes cleanly (no exceptions, no inflated retry counts), with the same successCount
  - (c) continues after one book throws, records the failure in `failedBooks`
  - (d) **empty book**: when the risk-engine returns 400 `Cannot calculate VaR on empty positions list`, the failure is recorded but classified `EMPTY` not `RETRYABLE`
  - (e) **retry**: on `ConnectException` for one book, retries up to 3 times then gives up; on transient 5xx, retries and eventually succeeds.
  Acceptance: `./gradlew :demo-orchestrator:test --tests "*DemoVaRBootstrapJobTest"`

- [ ] **3.2 SOD baseline alongside VaR** — extend `DemoVaRBootstrapJob` (or introduce a sibling `DemoBootstrapOrchestrator` if more than two jobs end up coupled) to also invoke `SodBaselineCaptureJob.runOnce()` per book after the VaR call succeeds. This unblocks the P&L Attribution route (412 Precondition Failed without an SOD baseline). Unit test: SOD baseline is called for every book that successfully posted VaR, not for books that failed.
  Acceptance: `./gradlew :demo-orchestrator:test --tests "*DemoVaRBootstrapJobTest.sod-baseline*"`

- [ ] **3.3 Wire bootstrap into startup + readiness endpoint** — in `demo-orchestrator/.../Application.kt`, after the seed phase completes and before the scheduled jobs register, invoke `DemoVaRBootstrapJob.runOnce()` in a coroutine (non-blocking so HTTP binds promptly). Expose `GET /demo/bootstrap-status` returning the current `BootstrapState` (in-memory). Acceptance test brings up demo-orchestrator with real Postgres + a recorded-calls fake risk-orchestrator (`ConcurrentLinkedQueue<RecordedVaRPost>`, pattern from `SimulatedTradingAcceptanceTest.kt`). Asserts that **each distinct book ID appears exactly once** in the recorded calls (not just count == 8) and that `/demo/bootstrap-status` transitions `NOT_STARTED → IN_PROGRESS → READY` within 30s.
  Acceptance: `./gradlew :demo-orchestrator:acceptanceTest --tests "*DemoVaRBootstrapAcceptanceTest"`

- [ ] **3.4 Verify firm aggregate populates (gateway suite)** — new acceptance test in **gateway** suite (`gateway/.../routes/CrossBookVaRFirmAfterBootstrapAcceptanceTest.kt`) that, against a real risk-orchestrator with VaR results seeded for all 8 books, asserts `GET /api/v1/risk/var/cross-book/firm` returns 200 with `isPartial == false`, `missingBooks` empty, and a non-zero positive firm VaR within the sanity range `$50,000 < firmVaR < $20,000,000` (per data-analyst). If this fails the bootstrap was not sufficient — add a follow-up `POST /api/v1/risk/var/cross-book` call to `DemoVaRBootstrapJob` in a separate commit and document the decision in the test.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*CrossBookVaRFirmAfterBootstrapAcceptanceTest"`

## Phase 4 — Production verification against the live demo

- [ ] **4.1 Audit harness committed** — move `/tmp/kinetix-audit/audit.mjs` into `scripts/demo-audit/audit.mjs`. Single dependency: Playwright (already in `ui/`). Emits `scripts/demo-audit/report.json`. Supports `--assert-clean` flag which enforces the hard gates and prints (informational, non-blocking) warnings for soft gates. Hard gates: (a) 0 page errors, (b) 0 HTTP 5xx, (c) no KPI cell equals `$0.00` after the bootstrap banner clears, (d) no visible spinner element after 8s of stable network on any tab. Soft gates (warn but don't fail): row count per tab, per-persona consistency (firm VaR identical across persona switches), per-book `varValue > 0`.
  Acceptance: `node scripts/demo-audit/audit.mjs --base https://kinetixrisk.ai --out /tmp/kinetix-audit-ci`

- [ ] **4.2 Redeploy and re-run audit (hard gates only)** — full redeploy of the demo (`./deploy/redeploy.sh`), wait for `/health` green AND `GET /demo/bootstrap-status` → `READY`, then run the audit script with `--assert-clean`. Capture the report under `docs/audits/2026-05-26-issues-1-after.json`. **Exit criterion**: hard gates all pass. If a hard gate fails after one re-run, escalate as `issues-1-followup` and STOP the loop — do not silently retry.
  Acceptance: `node scripts/demo-audit/audit.mjs --base https://kinetixrisk.ai --assert-clean --wait-for-bootstrap`

- [ ] **4.3 Firm KPI bar shows real numbers** — Playwright spec `ui/e2e/firm-kpi-bar.spec.ts` that loads `/`, waits for the bootstrap banner to disappear (`waitForSelector('[data-testid="bootstrap-banner"]', { state: 'detached' })`), then asserts each KPI cell (`NAV`, `UNREALISED P&L`, `VAR 1D 95%`, `NET DELTA`, `NET VEGA`) contains a non-zero formatted number — i.e. matches `^[-$£€]?[\d,]+(\.\d+)?[KMB]?$` and is not `$0.00`. Does **not** assert "no `—`" (an em-dash with a tooltip is still the correct state if data later goes missing, per ux-designer).
  Acceptance: `cd ui && npx playwright test e2e/firm-kpi-bar.spec.ts`
