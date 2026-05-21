# Allium v6: Spec-to-Code Reconciliation (2026-05-19 Weeding Sweep)

## Context

A six-agent `/weed` sweep on 2026-05-19 cross-checked all 24 `.allium` specs in
`specs/` against the full Kinetix codebase in both directions. Findings live in
`specs/divergences/2026-05-19/`:

- `initial-report.md` — synthesis, the 10-item punch list, counts, recommended steps.
- `group-a-core-market-refdata.md` — core, market-data, reference-data (24 findings, 4 regressions).
- `group-b-positions-trading.md` — positions, trading, limits, execution (12 findings).
- `group-c-risk-core.md` — risk, risk-models, intraday-pnl, eod-close (15 findings).
- `group-d-specialised-risk.md` — cpty, hierarchy, factor, liquidity, hedge, regime (~18 findings).
- `group-e-scenarios-governance.md` — scenarios, governance, ops (23 findings).
- `coverage-gaps.md` — code with no spec coverage (1 whole service + ~17 constructs).

Headline outcomes the sweep proved against the code:

- **4 regressions.** `OptionType`, `ExerciseStyle`, `BondSeniority`, `SwapDirection`
  Kotlin enums were marked resolved in `phase-0-classification.md` (2026-03-26) but
  never landed — `common/` still uses raw `String` everywhere.
- **SA-CCR ignores legal netting boundaries.** `SaCcrService.kt:44` lumps every
  counterparty trade into one synthetic netting set, materially misstating Basel III
  regulatory capital. The MC PFE pipeline gets this right; SA-CCR does not.
- **The regime engine is wired off.** `ScheduledRegimeDetector` is fully implemented
  but `risk-orchestrator/Application.kt:722-742` returns a stubbed `NORMAL`, leaving
  every downstream regime rule dormant.
- **The escalation invariant is breakable.** `AlertStatus.canTransitionTo` permits
  `TRIGGERED → ESCALATED`, so a manual escalate bypasses the acknowledgement audit trail.
- **`ai-insights-service` has zero spec coverage** — a whole LLM-fronted product
  (~41 source files) with no governance contract.
- ~23 spec-side corrections where code/proto/UI agree and only the spec lags.
- Single-spec `allium analyse` churns hundreds of false-positive `unreachableTrigger`
  findings because HTTP/REST origins are not modelled.

This plan ships, in dependency order: a tooling harness, the 10 P0 code bugs, the
spec-only corrections, the remaining code bugs, the new-spec / coverage-gap work,
and a full `surface RestApi` sweep across all 24 specs.

## Status

This plan is loop-ready for `/work-plan`. Each `- [ ]` checkbox is one
independently-committable change, ordered top-to-bottom by dependency, with an
`Acceptance:` command on the line directly after it. Advance end-to-end with
`/loop /work-plan plans/alium-v6.md`. The codebase must build and tests must pass
after every commit (CLAUDE.md "Commit Practices").

## Decisions applied

- **Scope: everything.** v6 covers the spec-only corrections, all new code-side
  bug fixes, the new-spec / coverage-gap work (`ai-insights.allium` and spec
  extensions for the ~10 uncovered constructs), and the full surface-block sweep.
- **Regime engine: re-enable.** `ScheduledRegimeDetector` is wired into
  `Application.kt`; the `NORMAL` stub is removed. The spec already presents the
  engine as live, so the code moves to the spec rather than the reverse.
- **Allium tooling: full surface-block sweep.** A `surface RestApi { … }` block is
  added to every spec so HTTP-origin triggers resolve in-spec, plus a workspace
  wrapper script (`plans/scripts/allium-workspace.mjs`) that checks/analyses the
  whole set at once.
- **Ambiguous direction rule.** Where code + proto + UI all agree against the
  spec, the **spec** is updated (lower risk than re-cutting a live wire contract).
  Where only the spec is internally coherent, the **code** is fixed. Applied
  case-by-case below; each checkbox states the direction.
- **`prices/stale` + `StaleInstrument`: spec-side alignment** *(overridable)*.
  Code, gateway and UI agree on `thresholdHours` / `lastUpdated` / `ageHours`;
  `market-data.allium` is updated to match. Re-cutting the wire contract is out of
  scope for v6.
- **`reporting.allium`: extend `regulatory.allium`** *(overridable)* rather than
  add a new spec file — the report-template engine is a regulator-facing capability
  and `regulatory.allium` already owns governance-facing surfaces.
- **ML prediction service: spec it, do not delete** *(overridable)*. `ml_server.py`
  is working code; deleting it is destructive and needs an explicit instruction.
  v6 adds a minimal `MLPredictionRequested` / `MLPredictionProduced` rule pair to
  `regime.allium`.
- **Minor naming-only divergences** (`id: String` vs `UUID`, `BookHierarchyMapping`
  vs `BookHierarchyEntry`, etc.) are resolved by a spec `@guidance` annotation, not
  by code churn — unless the wire contract is affected.
- **`@aspirational` means an `-- ASPIRATIONAL` `@guidance` comment, not an
  annotation.** Allium has no `@aspirational` annotation — `allium check` rejects
  it as an `error` (the only valid annotations are `@invariant` / `@guidance` /
  `@guarantee`). The spec set's established convention for "documented but not a
  live runtime contract" is an `-- ASPIRATIONAL` / `-- Aspirational` comment line
  inside a `@guidance` block (see `eod-close.allium:140`, `intraday-pnl.allium:127,178`,
  `counterparty-risk.allium:42`). Wherever a checkbox below says "mark
  `@aspirational`" / "downgrade to `@aspirational`", it means: add or extend a
  `@guidance` comment of that form on the rule/entity, keeping it a `rule` — never
  add a literal `@aspirational` token. Applies to checkboxes 2.3, 2.5, 4.10, 4.12.
- **Overlap with `docs/plans/spec-weeding-followup-2026-05-18.md`.** That plan
  remains the owner of its ~20 code items. v6 does **not** re-checkbox them; see
  the *Cross-reference* section. Only genuinely-new findings are checkboxed here.

## Contract changes pre-approved

The following cross a CLAUDE.md "Guardrails" line (API contract / DB migration).
They are pre-approved here so the loop does not stall — each is a bug fix that
aligns an implementation to its spec, and the user approved the full v6 scope:

- **SA-CCR route** gains a `nettingSetId` parameter and returns per-netting-set
  results (checkbox 1.3).
- **New Flyway migration** in `position-service` recreating the trade immutability
  trigger to cover `instrument_type` and `trader_id` (checkbox 1.8). Trigger
  recreation is transaction-safe — no `CREATE INDEX CONCURRENTLY`-class statement.
- **`YieldCurveTenorResponse.value` → `rate`** wire-field rename (checkbox 3.3).
- **Trader REST `id` → `trader_id`** wire-field rename, plus additive
  `created_at` / `updated_at` (checkbox 3.4).
- **`FactorRiskResponse`** gains `factorExposure` / `pnlAttribution` — additive,
  backward-compatible (checkbox 1.10).
- **Book base-currency reference data** (checkbox 1.9, approved by the user
  2026-05-21). A `base_currency` column is added to `position-service`'s existing
  `book_hierarchy` table via a new Flyway migration (plain `ALTER TABLE` —
  transaction-safe), and the field is surfaced additively through the existing
  `BookHierarchyRoutes` DTOs. No new table, service, or entity is created.
- **New spec files**: `ai-insights.allium` (checkbox 4.1). No new service, module,
  library, Kafka topic, or DB table is introduced — specs document existing code.

---

## Phase 0 — Tooling foundation

- [x] 0.1 Create `plans/scripts/allium-workspace.mjs` — a zero-dependency Node
      script that runs `allium check specs/` and `allium analyse specs/` over the
      whole spec set (resolving cross-spec `use` paths and triggers), parses the
      JSON, exits non-zero only on `error`-severity diagnostics, and prints a
      summary count of `unreachableTrigger` / `field.unused` findings as a
      baseline. This script is the acceptance command for every spec checkbox in
      Phases 2, 4 and 5.
      Acceptance: `node plans/scripts/allium-workspace.mjs && echo OK`

## Phase 1 — P0 code bugs (the punch list)

- [x] 1.1 Add the four shared instrument enums to `common/` — `OptionType`,
      `ExerciseStyle`, `BondSeniority`, `SwapDirection` — one type per file under
      `common/src/main/kotlin/com/kinetix/common/model/` per `core.allium:136-144`.
      Regression of `initial-report.md` #10/#11/#12/#28. TDD: enum + a parse/round-trip
      test in the same commit. Do not yet migrate the instrument fields (1.2).
      Acceptance: `./gradlew :common:test`

- [x] 1.2 Migrate the typed instrument fields off raw `String` onto the 1.1 enums:
      `EquityOption.optionType`/`exerciseStyle`, `CommodityOption.optionType`,
      `FxOption.optionType`, `CorporateBond.seniority`, `InterestRateSwap.payReceive`.
      Update serialization, DTOs and every consuming service; the build must stay
      green. Source: `group-a` C9.
      Acceptance: `./gradlew test` (compiles all modules + unit tests; full
      `./gradlew build` is not runnable here — it pulls in stack-dependent
      `:end2end-tests:end2EndTest`/`:loadTest`/`:gateway:loadTest` and exhausts
      Postgres connections running every module's acceptanceTest in parallel).

- [x] 1.3 SA-CCR per-netting-set computation. `SaCcrService.kt:44` synthesises one
      fake netting set `"$counterpartyId-SA-CCR"`; rewrite to group trades by their
      real ISDA/GMRA netting agreement, mirroring the MC PFE pipeline
      (`CounterpartyRiskOrchestrationService.kt:99-112`). `SaCcrRoutes.kt` gains a
      `nettingSetId` parameter and returns per-set results. Source: `group-d` C2 /
      `counterparty-risk.allium:415-444`.
      Acceptance: `./gradlew :risk-orchestrator:test :risk-orchestrator:acceptanceTest --tests "*SaCcr*"`

- [x] 1.4 Re-enable `ScheduledRegimeDetector`. Remove the stubbed `NORMAL` provider
      at `risk-orchestrator/Application.kt:722-742` and wire the real detector
      (debounce, listener, distributed-lock plumbing already implemented). Verify
      `RegimeHistory` is written and the downstream rules (`OverrideVaRParameters`,
      `CheckModelStaleness`, `HandleDegradedSignals`, early-warning / correlation-anomaly
      checks) have a live path. Source: `group-d` C3 / `regime.allium:155-425`.
      Acceptance: `./gradlew :risk-orchestrator:test :risk-orchestrator:acceptanceTest`

- [x] 1.5 Remove `TRIGGERED → ESCALATED` from `AlertStatus.canTransitionTo`
      (`notification-service/.../model/AlertModels.kt:24`) so the manual
      `POST /alerts/{id}/escalate` (`Application.kt:519`) can no longer skip the
      acknowledgement step. Add a test that escalating a `TRIGGERED` alert is
      rejected. Source: `group-e` E-S2C-02 / `EscalationRequiresAcknowledgement`.
      Acceptance: `./gradlew :notification-service:test :notification-service:acceptanceTest`

- [x] 1.6 Fix the auto-escalation audit event to carry `alertId` in the typed
      field. `AlertEscalationService.kt:34-42` stuffs the id into free-text
      `details`; populate `GovernanceAuditEvent.alertId` like the manual
      ack/escalate/resolve paths (`Application.kt:483-491,533-542,581-590`).
      Source: `group-e` E-S2C-01.
      Acceptance: `./gradlew :notification-service:test`

- [x] 1.7 Capture arrival price server-side. `OrderSubmissionService.kt:76,89`
      accepts caller-supplied `arrivalPrice`; replace with
      `current_mid_price(instrument_id)` from price-service at submission time per
      `execution.allium:266`. Remove `arrivalPrice`/`arrivalPriceTimestamp` from
      `SubmitOrderRequest.kt` and make the staleness check unconditional. Source:
      `group-b` C6.
      Acceptance: `./gradlew :position-service:test :position-service:acceptanceTest --tests "*OrderSubmission*"`

- [x] 1.8 New Flyway migration in `position-service` recreating the trade
      immutability trigger to also protect `instrument_type` and `trader_id`
      (`V14__fix_immutability_trigger_book_id.sql` currently covers 11 columns;
      `trading.allium:252-256` lists both extras as immutable). Use the next free
      `V` number; trigger recreation only — no `CREATE INDEX CONCURRENTLY`-class
      statement. Source: `group-b` C7.
      Acceptance: `./gradlew :position-service:integrationTest`

- [x] 1.9 Fix `IntradayPnlService.deriveBaseCurrency` (`IntradayPnlService.kt:341-343`)
      — currently hardcoded `"USD"` for every book. Resolve the book's base
      currency from book reference data per `intraday-pnl.allium:168`
      (`book_base_currency(baseline.book_id)`). Source: `group-c` C8.
      Approved approach (user, 2026-05-21): books are owned by `position-service`'s
      existing `book_hierarchy` table, so add a `base_currency` column there —
      no new table or service entity. Concretely:
        - New Flyway migration in `position-service` (next free `V` number):
          `ALTER TABLE book_hierarchy ADD COLUMN base_currency VARCHAR(3) NOT NULL
          DEFAULT 'USD'` — a plain `ALTER TABLE`, transaction-safe.
        - Add `baseCurrency: String` (default `"USD"`) to `BookHierarchyMapping`,
          `BookHierarchyTable`, and `ExposedBookHierarchyRepository`.
        - Add `baseCurrency` to the `BookHierarchyRoutes` request/response DTOs
          (additive, backward-compatible — defaults to `"USD"`).
        - Mirror the field onto risk-orchestrator's `BookHierarchyEntry` /
          `BookHierarchyEntryDto` and `HttpHierarchyDataClient`.
        - Change `IntradayPnlService.deriveBaseCurrency` to resolve via the
          `HierarchyDataClient` keyed on the in-scope `bookId` (the `recompute`
          parameter), falling back to `"USD"` only when no mapping exists.
          Update the affected unit/acceptance tests to supply the book's base
          currency through the fake client.
      TDD at every level (position-service unit + acceptance for the new column
      and route field; risk-orchestrator unit tests for client-driven resolution).
      Acceptance: `./gradlew :position-service:test :risk-orchestrator:test`

- [x] 1.10 Stop the `FactorContribution` mapper dropping fields. `GrpcRiskEngineClient.kt:171-181`
      ignores `factorExposure` and `pnlAttribution` even though the engine and
      proto populate both (`factor_server.py:235-258`). Add the two fields to the
      Kotlin `FactorContribution`, `FactorRiskResponse` DTO, and the mapper.
      Source: `group-d` C10 / `factor-model.allium:27-33`.
      Acceptance: `./gradlew :risk-orchestrator:test`

## Phase 2 — Spec corrections (code is right, spec lags)

- [x] 2.1 `risk.allium` — rename `book_group_id` → `portfolio_group_id` at lines
      331, 506, 519, 523. Code, proto (`risk_calculation.proto:172,186`),
      risk-engine, gateway tests and UI all use `portfolioGroupId`; the spec is the
      only laggard. Source: `initial-report.md` C1 / `group-c`.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.2 `market-data.allium` — align the stale-price contract to shipped code
      (Decisions applied): `QueryStalePrices` query param `staleAfter` → `thresholdHours`;
      `StaleInstrument` fields `last_price_at`/`staleness_seconds` → `lastUpdated`/`ageHours`
      plus the `status` field; document platform-tape-relative staleness semantics.
      Fix the `RiskFreeRate` `@guidance` precision note (`decimal(18,8)` →
      `decimal(28,12)`). Note that `VolSurfaceEvent` carries only `pointCount`.
      Source: `group-a` S2 + StaleInstrument + RiskFreeRate precision.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.3 `market-data.allium` — downgrade `ReconcileImpliedVsRealisedVol` to a
      seeder-only / `@aspirational` rule (no runtime `/reconcile` route, no
      `VolReconciliation` DTO, no UI dashboard component exist — only the seeder
      acceptance test). Source: `group-a` aspirational finding.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.4 `reference-data.allium` — add `@guidance` for: variant `currency`
      duplication on `EquityFuture`/`CommodityFuture`/`InterestRateSwap`/`CorporateBond`;
      `EquityOption.contract_multiplier` default of 100; `InterestRateSwap.floatSpread`/
      `dayCountConvention` non-null defaults; `DividendYield.exDate` as typed
      `LocalDate`; and fix `CreateTrader` to look up `Desk` by id, not `name`.
      Source: `group-a` spec bugs + regressions of `initial-report.md` #20/#27.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.5 `intraday-pnl.allium` — mark `IntradayPnlState` and `InitialiseIntradayState`
      `@aspirational` (the live design is the stateless `recompute()` against the
      repo; no state class or `SodBaseline.created` listener exists). Close the
      stale open question at line 414 — `IntradayVaRTimeline` is already shipped in
      a separate table; document the design. Source: `group-c` S4 + IntradayVaRTimeline.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.6 `scenarios.allium` — three fixes: add the missing `pending_approval → draft`
      arc to the `SavedScenario` transitions block (E-S2C-04); update `ReplayResult`
      to the per-position shape the regulatory-service actually returns, or document
      both views (E-S2C-06); update `ReverseStressResult` to the per-instrument
      `shocks` contract the engine implements (E-S2C-05).
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.7 `discovery-valuation.allium` — reconcile the `FetchOutcome` enum /
      `FetchResult` value type with the Kotlin sealed `FetchResult`
      (`FetchSuccess`/`FetchFailure`): either annotate the divergence in `@guidance`
      or align the spec to the sealed shape. Source: `group-e` E-C2S-04 / `group-a` S5.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.8 `alerts.allium` — correct the `BudgetBreachExtractor` description: it
      returns the actual `utilisationPct` from the sentinel, not a `1.0` flag
      (E-S2C-03). Add `@guidance` location hints to the three location-less
      `deferred` blocks: `extract_metric`, `promote_severity`, `escalation_channels`
      (E-S2C-17).
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.9 `regulatory.allium` — add the `ensures: GovernanceTransitionOccurred(...)`
      clauses the code already emits: `RegisterModel`/`ValidateModel`/`ApproveModel`/
      `RetireModel` emit `MODEL_STATUS_CHANGED`; `ApproveSubmission`/`AcknowledgeSubmission`
      emit their events. Add `created_at` to `RegulatorySubmission`. Source:
      `group-e` E-S2C-14/15/16.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.10 `regime.allium` — fix the invariant section (lines 438-444) to match the
      `ApplyRegimeParameters` guidance and code (CRISIS 5-day + EWMA path):
      `AdaptiveRegimeParameterProvider` implements the guidance values. Rename
      `RegimeHistory.signals_at_transition` → `signals` (or annotate). Source:
      `group-d` regime invariant inconsistency.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.11 `factor-model.allium` — apply the "persisted as `factorName: String`"
      `@guidance` note to `FactorDefinition.factor` (the same note already exists
      on `InstrumentFactorLoading`). Source: `group-d` FactorDefinition naming.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.12 `liquidity.allium` — clarify whether `PositionLiquidityResult.liquidity_tier`
      is the instrument reference tier or a recomputed per-position ADV-fraction
      tier (document the ADV-fraction thresholds if the latter); reconcile the
      field-name drift (`position_notional`/`liquidation_days`/`lvar`/`concentration_flagged`
      vs `marketValue`/`horizonDays`/`lvarContribution`/`concentrationStatus`); rename
      `LiquidityRiskSnapshot.lvar` to `portfolio_lvar` (matching the persisted
      column) and fix the `LVaRExceedsVaR` invariant reference. Source: `group-d`.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 2.13 `hierarchy-risk.allium` + `hedge.allium` — annotate `BookHierarchyMapping`
      vs code class `BookHierarchyEntry`; document the marginal-VaR formula
      (sum-of-contributions vs drop-out reaggregation) on `ComputeMarginalContribution`;
      annotate `HedgeRecommendation.id` as serialised-string of a `UUID`. Source:
      `group-d` naming items.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

## Phase 3 — Remaining code bugs

- [x] 3.1 `ForwardCurve.assetClass: String` → `core/AssetClass`
      (`common/.../ForwardCurve.kt:7`, rates-service DTOs). Source: `group-a`.
      Acceptance: `./gradlew :common:test :rates-service:test`

- [x] 3.2 `InstrumentLiquidity.assetClass: String` → `core/AssetClass`
      (`reference-data-service/.../model/InstrumentLiquidity.kt:10`). Source: `group-a`.
      Acceptance: `./gradlew :reference-data-service:test`

- [x] 3.3 Rename `YieldCurveTenorResponse.value` → `rate` (gratuitous rename;
      inconsistent with `Tenor.rate`/`RiskFreeRate.rate` and `InterpolatedTenor`
      in `market-data.allium:140-145`). Update the rates-service route and any
      consumer. Source: `group-a`.
      Acceptance: `./gradlew :rates-service:test :rates-service:acceptanceTest`
      Verified 2026-05-21: `:rates-service:test` green and acceptance sources
      compile; `:rates-service:acceptanceTest` could not run in the loop env (no
      Docker daemon — all 4 specs fail at `DockerClientProviderStrategy` before
      any test logic). Needs a Docker-enabled run (CI) to fully confirm the wire
      shape.

- [x] 3.4 Surface `Trader.createdAt` / `updatedAt` (present in the DB and migration
      but dropped by the Kotlin model and REST DTOs) and rename the REST field
      `id` → `trader_id` to match `reference-data.allium:62-69` and the gRPC proto.
      Source: `group-a`.
      Acceptance: `./gradlew :reference-data-service:test :reference-data-service:acceptanceTest --tests "*Trader*"`
      Verified 2026-05-21: `:reference-data-service:test` green and acceptance
      sources compile; `:reference-data-service:acceptanceTest` could not run in
      the loop env (no Docker daemon — `DockerClientProviderStrategy`). Needs a
      Docker-enabled run (CI) to confirm the new `TraderRoutesAcceptanceTest`.
      Note: the gRPC `trader_lookup.proto` `GetTraderResponse` field is still
      `id` (not `trader_id`) — out of scope for this REST-only checkbox; a proto
      rename would be a separate contract change.

- [x] 3.5 Single-source `unexplained_pnl`. `IntradayPnlService.kt:138-140` re-derives
      the residual by subtracting nine components instead of using
      `attribution.unexplainedPnl` already returned by `pnlAttributionService.attribute()`.
      Use the attribution service's value. Source: `group-c`.
      Acceptance: `./gradlew :risk-orchestrator:test`

- [x] 3.6 Fix the hedge candidate filter. `HedgeRecommendationService.kt:230-249`
      filters on `assetClass !in {"ILLIQUID"}` — dead code, since `ILLIQUID` is a
      liquidity tier. Filter on `liquidityTier`, move tier/eligibility filtering
      **before** `.take(MAX_CANDIDATES)`, and rank by liquidity before truncation
      per `hedge.allium:179-198`. Extends the 2026-05-18 plan's dead-filter item
      with the truncation-ordering bug. Source: `group-d`.
      Acceptance: `./gradlew :risk-orchestrator:test`

- [x] 3.7 Fix hedge `data_quality`. `HedgeRecommendationService.kt:279` synthesises
      `priceAgeMinutes` from `liq.advStale` instead of the real quote timestamp;
      `AnalyticalHedgeCalculator.kt:103` then applies the 15-minute threshold to a
      fake age. Read the actual price timestamp from `getLatestPrice(...)`. Source:
      `group-d` / `hedge.allium:44-51`.
      Acceptance: `./gradlew :risk-orchestrator:test`

- [x] 3.8 Emit the missing position-resolution degradation flags in the engine.
      `position_resolver.py:23-66` only ever emits `VOL_SURFACE_*` flags; add
      `OPTION_EXPIRED_INTRINSIC`, `OPTION_NO_VOL`, `SWAP_NO_CURVE` per
      `discovery-valuation.allium:107-126`. Source: `group-e` E-S2C-13.
      Acceptance: `cd risk-engine && uv run pytest -m unit -k position_resolver`

## Phase 4 — New specs & coverage extensions

- [x] 4.1 Write `specs/ai-insights.allium` — a new spec for `ai-insights-service`
      (~41 source files, currently zero coverage). Required surfaces: chat
      request/response contract, conversation persistence, the citation-verification
      invariant (every numeric claim cites a source), the banned-phrase policy
      invariant, MCP read-only tool contracts, and the `ClaudeAgentChatClient` /
      `CannedChatClient` switch. Source: `coverage-gaps.md` §ai-insights-service.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.2 Extend `execution.allium` — add `VenueSession` / `VenueCutoff` entities,
      a `DayOrderExpiry` rule driven by venue-cutoff timestamps, a FIX-message-log
      durability invariant (every wire message persisted), and promote
      `CancelAttempt` to a first-class entity with its `CancelAttemptStatus`
      lifecycle. Source: `coverage-gaps.md` §fix-gateway + `group-b` `cancel_attempts`.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.3 Extend `risk.allium` — add a `ReplayRun` entity + `RecordReplayRun` rule
      (the `replay_runs` table / `RunReplayRoutes` audit trail, per ADR-0018), a
      `CompareRuns` rule (`RunComparisonService`), and surface the interactive
      What-if / Rebalancing services. Source: `coverage-gaps.md` + `group-c`.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.4 Extend `factor-model.allium` — add a `BrinsonAttribution` rule and
      `AttributionResult` entity (`risk-engine/brinson.py`, gRPC `AttributionService`,
      gateway `BenchmarkAttributionRoutes`), and add the `FactorPnlAttribution`
      entity / `ComputeFactorPnlAttribution` surface that has no Kotlin
      representation today. Source: `coverage-gaps.md` + `group-d` C10 tail.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.5 Extend `audit.allium` — add a `ReplayDlqMessage` rule (must re-hash with
      sequence-preserving semantics) with an authz invariant restricting replay to
      the operator role; tighten `RecordGovernanceAuditEvent` to name the consumed
      Kafka topic and require idempotency on `(source_service, event_id)`. Source:
      `coverage-gaps.md` §Audit DLQ replay + §governance consumer.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.6 Extend `alerts.allium` — spec the snooze workflow (`snoozed_until` field,
      snooze rule, dedup interaction), add `EscalateAlertManually` and
      `ResolveAlertManually` rules with their preconditions and audit `ensures`,
      and add an `AnomalyDetected` trigger + `RaiseAnomalyAlert` rule for the
      notification-service `AnomalyEvent` consumer. Source: `group-e` E-C2S-01/02/03
      + `coverage-gaps.md` §anomaly.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.7 Extend `market-data.allium` — add a `requires: psd(values)` clause to
      `IngestCorrelationMatrix` and a `PsdValidationResult { is_psd, min_eigenvalue,
      tolerance }` value type, modelling the `CorrelationPsdValidator` that already
      rejects non-PSD matrices. Source: `group-a` coverage gap.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.8 Extend `risk-models.allium` — add a `RunReverseStressTest` rule
      (`risk-engine/reverse_stress.py`) and a `@guidance` note on
      `CalculatePricingGreeks` for the numerical cross-Greeks fallback path
      (`cross_greeks.py`). Source: `group-c` coverage gaps.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.9 Extend `regulatory.allium` — add `ReportTemplate` and `GenerateReport`
      rules for the report-template engine (`report_templates`/`generated_reports`,
      `ReportRoutes`, V56 migration). Add `RunHistoricalReplay` coverage for the
      `historical_scenario_periods`/`historical_scenario_returns` tables. Source:
      `coverage-gaps.md` §reporting + §historical scenario tables.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.10 Extend `regime.allium` — add a minimal `MLPredictionRequested` /
      `MLPredictionProduced` rule pair for `ml_server.py` / `MLPredictionService`
      so the ML output is governable (model-version pinning, audit). Annotate
      `RegimeModelConfig` as `@aspirational` with a `TODO(REG_D-03)` location hint.
      Source: `coverage-gaps.md` §ML prediction + `group-d` RegimeModelConfig.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.11 Extend `counterparty-risk.allium` — add a persisted SA-CCR history
      entity (`SaCcrResultsTable`), a `CounterpartyExposureHistory` entity, a
      `NettingSetAssignment` rule (trade ↔ netting-set association), and an
      `InternalMargin` entity + `CalculateMargin` rule (`MarginCalculator` /
      `MarginEstimate`). Source: `group-d` + `coverage-gaps.md` §MarginCalculator.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.12 Extend `hierarchy-risk.allium` — annotate `ScheduledHierarchyAggregation`
      and the `TriggerCROReport` delegation as `@aspirational` (no scheduler exists;
      `CroReportRoutes` returns synchronous JSON, not a delegated PDF/CSV). Source:
      `group-d` aspirational findings.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.13 Extend `positions.allium` + `trading.allium` — add a `PositionNote`
      entity with CRUD rules (`position_notes` table, retention/audit obligations)
      and a `TradeStrategy` entity with the `StrategyType` enum and CRUD rules
      (drives P&L attribution). Source: `group-b` coverage gaps.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [x] 4.14 Add the remaining design-note coverage: a `## Out of scope` callout in
      `eod-close.allium` / `trading.allium` for `demo-orchestrator`; a distributed-lock
      protocol note for scheduled jobs in `core.allium`; and declare the Kafka
      topics + payload schemas for the budget-breach / factor-concentration /
      liquidity-concentration / regime-event publishers in `alerts.allium`. Source:
      `coverage-gaps.md` + `group-d`.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

## Phase 5 — Surface-block sweep

Add a `surface RestApi { … }` block to every spec so each HTTP-origin trigger
resolves in-spec, killing the false-positive `unreachableTrigger` findings that
single-spec `allium analyse` produces. Batched by spec group; each batch is one
commit.

- [ ] 5.1 Group A — add `surface RestApi` blocks to `core.allium`,
      `market-data.allium`, `reference-data.allium`.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [ ] 5.2 Group B — add `surface RestApi` blocks to `positions.allium`,
      `trading.allium`, `limits.allium`, `execution.allium`.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [ ] 5.3 Group C — add `surface RestApi` blocks to `risk.allium`,
      `risk-models.allium`, `intraday-pnl.allium`, `eod-close.allium`.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [ ] 5.4 Group D — add `surface RestApi` blocks to `counterparty-risk.allium`,
      `hierarchy-risk.allium`, `factor-model.allium`, `liquidity.allium`,
      `hedge.allium`, `regime.allium`.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [ ] 5.5 Group E — add `surface RestApi` blocks to `scenarios.allium`,
      `scenario-lifecycle.allium`, `discovery-valuation.allium`, `alerts.allium`,
      `alert-escalation.allium`, `audit.allium`, `regulatory.allium`, plus
      `ai-insights.allium` if not already covered by 4.1.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

- [ ] 5.6 Final verification — run `node plans/scripts/allium-workspace.mjs`,
      confirm zero `error`-severity diagnostics across the whole set and that the
      `unreachableTrigger` count has dropped substantially from the 0.1 baseline.
      Record the new baseline in the script output / a comment.
      Acceptance: `node plans/scripts/allium-workspace.mjs`

---

## Cross-reference — items owned by the 2026-05-18 plan

`docs/plans/spec-weeding-followup-2026-05-18.md` remains the owner of these
code-side fixes; the 2026-05-19 sweep re-confirmed them open but they are **not**
re-checkboxed here:

- risk-engine FX option curve emitter (`dependencies.py:245` — EUR/USD asymmetry).
- Audit `alert_id` dropped on **persistence** (audit-service column + consumer +
  `AuditHasher`). Distinct from v6 checkbox 1.6, which fixes the notification-service
  **producer** path.
- `AutoResolveAlerts` never emits `ALERT_RESOLVED` audit event.
- `ReplayCustomDateRange` 252-day cap unenforced.
- Liquidity fraction-of-ADV classifier + inert `default_participation_rate` +
  missing `ScheduledLiquidityComputation`.
- PSD repair for `correlation_override` in `StressScenarioService`.
- CounterpartyRisk `peak_pfe = 0` on the mark-to-market path.
- `netting_set_breakdown` dropped from `CounterpartyExposureResponse`.
- `run_label` transition enforcement at the recorder + `PromotedJobsImmutable` guard.
- `STALE_GREEKS` never assigned; `CheckModelStaleness` not implemented (the latter
  is partially unblocked once v6 checkbox 1.4 re-enables the regime engine).
- `SaveReverseStressAsScenario` not implemented; `ADV_CONCENTRATION` hook not wired.

## Out of scope

> Deferred — not checkboxed so the loop does not attempt them:

- **Re-cutting the `prices/stale` wire contract.** v6 aligns the spec to the
  shipped code (checkbox 2.2); changing `thresholdHours` back to an ISO-8601
  `staleAfter` across price-service, gateway and UI is a separate effort.
- **`IntradayPnlState` as a real in-memory state machine.** v6 marks it
  `@aspirational` (checkbox 2.5); building the stateful entity + `SodBaseline.created`
  listener is future work.
- **`AutoClosePromoteOnRiskResult` event-driven rewrite.** Already correctly tagged
  `@aspirational` in `eod-close.allium`; the synchronous `ScheduledAutoCloseJob` is
  the live design.
- **Deleting `ml_server.py` / `MLPredictionService`.** v6 specs the service
  minimally (checkbox 4.10). Removal needs an explicit decision.
- **Per-asset-class roll-up for `ReverseStressResult`.** The pipeline is
  per-instrument end-to-end; v6 aligns the spec to that (checkbox 2.6) rather than
  building the roll-up.
- **TimescaleDB continuous aggregates, per-service Redis caches, feed simulators,
  Prometheus/observability plumbing.** Performance / scaffolding concerns the sweep
  classified as intentional out-of-spec gaps.
