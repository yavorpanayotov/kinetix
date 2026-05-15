# Demo Follow-Up — Acceptance Criteria Remediation

Companion to [demo-review.md](demo-review.md). Audited 2026-05-14 after Phases 0–3 landed.

The headline finding from the audit: Phase 0's `demo-tape-generator` exists and is wired into `volatility-service` and `rates-service`, but **`price-service`, `correlation-service`, and `risk-orchestrator` P&L still synthesise data independently**. Three of the five Gap 0 failure modes Marcus called out in v2 remain. Two of five data-realism acceptance boxes have no real test backing them. The credibility argument the demo was rebuilt to make does not yet hold.

Plan below addresses all 9 audit gaps in priority order, sized into three sequential PRs that match the natural blast radius.

---

## Audit recap

| # | Section | Box | Status | File / location |
|---|---|---|---|---|
| 1 | Data realism | P&L from positions × prices | FAIL | `risk-orchestrator/.../seed/DevDataSeeder.kt:282-303` |
| 2 | Data realism | Kupiec 1–5 exceptions | FAIL | `common/.../DemoTapeTest.kt:70-90` (tautology) |
| 3 | Data realism | Realised vs implied vol | PARTIAL | `price-service/.../seed/DevDataSeeder.kt:76-105` (own GBM) |
| 4 | Data realism | Correlation vs covariance | FAIL | `correlation-service/.../seed/DevDataSeeder.kt:121-151` (constants) |
| 5 | Data realism | Stress window drawdown | PARTIAL | derived from #1, #3, #4 |
| 6 | Demo experience | Anomaly UI — missing 5Y node | FAIL | no yield-curve chart in `ui/src` |
| 7 | Engineering | Audit hash chain settles <90s | FAIL | no test enforces SLA |
| 8 | Engineering | Every trade has real `traderId` | PARTIAL | `position-service/.../BookTradeCommand.kt:44` (nullable) |
| 9 | Engineering | Golden LCG fixture pinned in CI | PARTIAL | determinism tests pass, no checked-in hash |
| 10 | Workflow | Stress excession alert ready to fire | PARTIAL | `notification-service/.../seed/DevDataSeeder.kt:354-373` (no `stress-*`) |

---

## PR 1 — Data realism (the credibility blocker)

Goal: every consistency check in the plan reconciles. Without this PR, the demo still fails the first 10 minutes a quant buyer spends on it.

### Scope

1. **Wire `correlation-service` seeder to the tape.**
   - `correlation-service/.../seed/DevDataSeeder.kt:121-151` — replace hardcoded `AAPL:MSFT to 0.82` block with a call to `CurveAndVolDerivations.realisedCorrelation(tape, window=252)` (the helper already exists in `common`).
   - Stress-window correlations come from the same call; the regime calendar drives the spike automatically.
   - Add `CorrelationReconciliationAcceptanceTest` — asserts `|seededCorrelation[i,j] - empiricalCovariance(tape[i], tape[j])| < 0.05` for every pair.

2. **Wire `price-service` seeder to the tape.**
   - `price-service/.../seed/DevDataSeeder.kt:76-105` — drop the local GBM. Replace with tape-driven daily returns + intraday interpolation.
   - This is the upstream change that makes #3 (vol reconciliation) free.
   - Add `PriceTapeConsistencyAcceptanceTest` — asserts price-service's seeded daily closes match the tape's prices to 1e-6.
   - Add `VolReconciliationAcceptanceTest` in `volatility-service` — asserts `|impliedATM(t) - realisedVol(tape, window=21, ending=t)| < riskPremium` for sample dates.

3. **Derive `risk-orchestrator` P&L from positions × tape moves.**
   - `risk-orchestrator/.../seed/DevDataSeeder.kt:282-303` — delete `BOOK_PNL_PROFILES` constants. Build a `PnLAttributionDeriver` that walks positions × tape price moves and emits per-book delta/gamma/vega/theta/residual rows.
   - For options books, use the same Greeks the risk-engine produces (call out via gRPC during seed, or replicate the Black-Scholes formula in `common` — pick the cheaper one).
   - Stress-window drawdowns fall out for free.

4. **Replace the tautology Kupiec test.**
   - `common/.../DemoTapeTest.kt:70-90` — remove the `break`/`x shouldBe x` placeholder.
   - New `KupiecBacktestAcceptanceTest` — runs historical VaR over the seeded P&L series for 252 days; asserts exception count ∈ [1, 5] at 99% confidence; logs the test statistic for debugging.
   - Requires `historicalVaR` to honour `endDay` for window sourcing — fix that first (current impl ignores it per the comment in the existing test).

### Out of PR 1

- Vol surface shape calibration beyond ATM (skew, term structure) — deferred unless reconciliation reveals it's needed.
- Risk-engine integration during seed (heavy lift) — replicate Greeks formula in `common` if it's small.

### Acceptance for PR 1

- `./gradlew :correlation-service:acceptanceTest :price-service:acceptanceTest :volatility-service:acceptanceTest :risk-orchestrator:acceptanceTest :common:test` green
- Manual: invoke `/api/v1/admin/demo-reset?scenario=stress`, query VaR backtest endpoint, confirm 1–5 exceptions; query correlation matrix during stress window, confirm cross-asset spike

---

## PR 2 — Engineering polish

Goal: close the three engineering boxes that are PARTIAL or FAIL. None block the demo from running, all block sign-off claims.

### Scope

5. **Audit hash chain settle SLA test.**
   - New `audit-service/.../AuditChainSettleAfterResetIntegrationTest` — POST to `/api/v1/admin/demo-reset`, poll `audit_events` lag, assert `lag == 0` within 90s of the reset returning 200.
   - Run under `./gradlew :audit-service:integrationTest` (real Postgres + Kafka via Testcontainers).

6. **Make `BookTradeCommand.traderId` non-null.**
   - `position-service/.../BookTradeCommand.kt:44` — change `val traderId: TraderId? = null` to `val traderId: TraderId`.
   - Drop the `if (command.traderId != null)` validation gate (line 69) — validate unconditionally.
   - Update all callers — including `TradeTapeGenerator.kt:92` which uses `?.let`. If a book is missing from `DemoTraderRoster.BOOK_TO_DESK`, fail fast at seed time with a clear error rather than emitting a null trade.
   - Update existing tests that pass `traderId = null`.

7. **Pin LCG output via golden SHA-256 fixtures.**
   - For each generator (`TapeRng`, `DemoTape`, `TradeTapeGenerator`, `CurveAndVolDerivations`), add one assertion of the form:
     ```kotlin
     val golden = "ab12cd34…"  // sha256 of stringified output for seed=42
     sha256(generate(seed = 42)).toHex() shouldBe golden
     ```
   - Generate the digests once, commit them. Any algorithm change without an intentional baseline update fails CI.

### Acceptance for PR 2

- `./gradlew :audit-service:integrationTest :position-service:test :common:test` green
- Spec drift gone: `BookTradeCommand.traderId` non-null in `position-service/src/main` and every caller compiles

---

## PR 3 — Demo polish (UI + workflow)

Goal: close the two remaining UX/workflow gaps. Lowest blast radius, lands last.

### Scope

8. **Yield-curve chart with `interpolated` marker.**
   - New `ui/src/components/YieldCurveChart.tsx` — fetches from `rates-service` GET `/api/v1/curves/{ccy}`. Renders curve points, marks any point with `interpolated: true` with a hollow marker + tooltip "Interpolated — source node unavailable".
   - Place on the existing rates dashboard surface (or add a dashboard tile if none exists today).
   - Playwright test in `ui/e2e/yield-curve-anomaly.spec.ts` — mocks the rates response with the seeded missing-5Y anomaly, asserts the interpolated marker is visible.

9. **Stress-scenario alert events ready to fire.**
   - `notification-service/.../seed/DevDataSeeder.kt` — add limit-breach alerts for `stress-vol` (notional + concentration), `stress-momentum`, `stress-credit` matching the seeded breaches in `position-service/.../seed/StressScenario.kt:263,273`.
   - Acceptance test: load `stress` scenario, assert the notification queue surfaces 2+ pre-fired alerts when the demo presenter opens `AlertDrillDownPanel`.
   - Update `docs/demos/stress.md` step 6 narrative if the alert IDs need to match what the script references.

10. **Cosmetic — option non-convergence shows `N/A` not `—`.**
    - `ui/src/components/PositionGrid.tsx:543` — change the em-dash render to literal `N/A` to match the anomaly contract in `demo-review.md` Gap 8.
    - Trivial; bundle into PR 3.

### Acceptance for PR 3

- `cd ui && npx playwright test yield-curve-anomaly.spec.ts` green
- `./gradlew :notification-service:acceptanceTest` green
- Manual: load each scenario, walk the corresponding `docs/demos/*.md` script end-to-end, every step has a UI surface

---

## Sequencing

| PR | Title | Estimate | Blocks |
|---|---|---|---|
| 1 | Data realism cluster | 1.5–2 weeks | the credibility argument |
| 2 | Engineering polish | 0.5 week | sign-off claims |
| 3 | Demo polish (UI + workflow) | 0.5 week | a clean walk-through of every script |

Total: 2.5–3 weeks. PRs are independent — PR 2 and PR 3 can run in parallel after PR 1's tape-wiring lands, since both depend on the tape being authoritative.

---

## Acceptance criteria for the follow-up itself

The demo-review acceptance section flips fully green when:

**Data realism**
- [x] Kupiec test asserts 1–5 exceptions over 252 days; not a tautology
- [x] Correlation matrix is derived from tape, not constants; reconciliation test passes
- [x] `price-service` prices match tape to 1e-6
- [x] Realised vs implied vol reconciliation test passes within risk premium
- [x] P&L attributions are derived from positions × tape moves; stress drawdown visible

**Demo experience**
- [x] Yield-curve chart renders missing-5Y anomaly with interpolated marker
- [x] (Cosmetic) options non-convergence shows `N/A`

**Engineering**
- [x] Audit hash chain settle <90s pinned by integration test
- [x] `BookTradeCommand.traderId` non-null; no `?.let` in seeder
- [x] Golden SHA-256 committed for every tape generator

**Workflow**
- [x] `stress-*` alerts seeded in `notification-service`; `stress.md` step 6 has data

When those land, the audit punch list is empty and Marcus stops complaining for real.

---

## Out of scope for this follow-up

- Risk-engine integration during seed (use a `common`-side Black-Scholes for now — replace later if accuracy requires it)
- Vol surface skew/term-structure calibration beyond ATM
- Anything from `demo-review.md` § Out of scope (regulatory scenario, intraday P&L engine)
