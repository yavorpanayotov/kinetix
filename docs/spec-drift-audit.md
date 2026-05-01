# Spec/Code Drift Audit — 2026-04-30

Read-only audit of all 24 Allium specs (`specs/*.allium`) against implementation code, run via four parallel `weed` agents in check mode. ~110 divergences flagged. Nothing modified by the audit.

This document is the work-tracking source of truth for resolving the divergences. Sessions that pick up this work should re-read this file before starting.

## Status legend

- ☐ open
- ⚠ needs-decision (quant/business/architecture call required)
- ✓ done
- ↪ deferred (aspirational; out of scope for current cleanup)

P2 aspirational items carry a Batch-I triage suffix:

- **(triage: implement)** — keep in spec; queue real implementation work in a future sprint.
- **(triage: defer)** — keep in spec; not on the current roadmap; revisit when a concrete consumer materializes.
- **(triage: deprecate-from-spec)** — current code is the desired behaviour; spec is wrong. Remove or rewrite the spec rule.

---

## P0 — Production correctness (highest priority)

1. ✓ **`MarketRegimeEventConsumer` defined but never started.** `notification-service/src/main/kotlin/com/kinetix/notification/Application.kt:235` boots `riskResultConsumer`, `anomalyEventConsumer`, `limitBreachEventConsumer` but not `MarketRegimeEventConsumer`. Regime-change alerts never fire. Fix: add to startup. Spec: `alerts.allium:413-454`.

2. ✓ **Wrong-way risk logic ignores position sector.** Resolved per `/quant` review: the spec is right (Basel CRE54 specific WWR requires structural correlation between counterparty and position sectors, not just counterparty alone — coarse counterparty-only flags generate systematic false positives across cross-sector positions). Implemented strict sector-match: new `WrongWayRiskSectorGroup` enum with sector-string taxonomy (`BANK`/`BROKER_DEALER`/`INSURANCE`/etc → `FINANCIALS`; `SOVEREIGN`/`GOVERNMENT`/`SUPRANATIONAL`/etc → `SOVEREIGN`; energy/utilities and real-estate groups added; everything else → `OTHER` which never fires). `computeWrongWayRiskFlags` now takes `positions` and fires once per matching group (not per instrument). Empty-position counterparty no longer fires — there's no position to match against. Aspirational structured `WrongWayRiskFlag` migration deferred to a follow-on; spec @guidance documents the taxonomy. Tests: new `WrongWayRiskSectorGroupTest` for the taxonomy, plus four new sector-match scenarios in `CounterpartyRiskOrchestrationServiceTest`.

3. ⚠ **VaR-vs-pricing Greeks confusion in intraday P&L.** Spec at `intraday-pnl.allium:225-231` mandates pricing sensitivities, not VaR sensitivities from `greeks.py`. **Quant verdict (2026-05-01):** Path A — build `SodGreekSnapshot` infrastructure. VaR Greeks are categorically the wrong quantity (`d(VaR)/dS` vs `dV/dS`), not a degraded approximation. **Phase 1 complete (2026-05-01):** persistence layer (`V53__create_sod_greek_snapshots.sql` + `SodGreekSnapshot` model + `SodGreekSnapshotRepository` + Exposed impl) was already in place; `IntradayPnlService` now reads from it via the same VaR-Greek fallback pattern that `PnlComputationService` already uses, with five new test scenarios covering pricing-Greek-preferred, empty-rows fallback, null-repo fallback, per-instrument fallback, and cross-Greek (vanna/volga/charm) propagation. **Phase 2 deferred:** the SOD population path (`PricingGreeks` gRPC method on risk-engine vs Kotlin closed-form module + SOD job that writes to `sod_greek_snapshots`) is a real architectural decision and remains queued. Until Phase 2 lands, the new repository returns empty maps and consumers fall back to VaR Greeks — same state `PnlComputationService` is in today, but now both consumers will pick up the pricing Greeks atomically when the SOD job lands.

4. ✓ **Missing input validation across market-data ingestion.** Spec `requires` clauses unenforced in routes:
   - Price `>= 0` — `price-service/.../PriceRoutes.kt:115-122` (spec `market-data.allium:148-149`).
   - Yield curve `tenors.count >= 1` — `rates-service/.../RatesRoutes.kt:81-91` (spec `market-data.allium:165-167`).
   - Risk-free rate `tenor != ""` — `RatesRoutes.kt:118-198` (spec `market-data.allium:184-194`).
   - Forward curve `points.count >= 1` — same file (spec `market-data.allium:202-213`).
   - Vol surface `points.count >= 1` + positive vols — `volatility-service/.../VolatilityRoutes.kt:121-133` (spec `market-data.allium:217-227`).
   - Correlation matrix `values.count = labels.count^2` — `correlation-service/.../CorrelationRoutes.kt:43-62` (spec `market-data.allium:253-264`).

5. ✓ **`LIMIT_BREACH` alert pipeline absent from `alerts.allium`.** Whole flow exists in code (`LimitBreachEventConsumer`, `LimitBreachRule`, `limits.breaches` Kafka topic, in-app delivery, `AlertType.LIMIT_BREACH` in `core.allium:101`) but the spec doesn't model it. **Spec edit only** — distill the flow into `alerts.allium`.

6. ✓ **`UniqueLimitDefinition` invariant.** Audit miss — the unique constraint *is* present in `V5__create_limit_hierarchy_tables.sql:12` (`UNIQUE (level, entity_id, limit_type)`); only the Exposed Kotlin table object failed to mirror it, which is what the audit picked up on. Resolved by adding `LimitDefinitionUniqueConstraintIntegrationTest` to lock the DB-level constraint in as a regression guard, and a doc comment on `LimitDefinitionsTable` pointing at the migration. No new Flyway migration needed.

7. ✓ **`AlertOnBudgetBreach` unimplemented.** Explicit `TODO(HIER-03)` in `risk-orchestrator/.../BudgetUtilisationService.kt:63-71` — breach is logged, no alert published. Spec rule (`hierarchy-risk.allium:241-255`) mandates `RISK_BUDGET_EXCEEDED` via notification-service.

8. ✓ **`AcknowledgeSubmission` discards regulator timestamp.** `regulatory-service/.../SubmissionService.kt:78-98` uses `Instant.now()` instead of regulator-supplied `acknowledged_at`. Route at `SubmissionRoutes.kt:74-87` accepts no body. Spec rule `AcknowledgeSubmission` (`regulatory.allium:354-359`).

## P1 — Behavioural divergence

9. ✓ **Three coexisting `LiquidityTier` enums.** Resolved: `InstrumentLiquidityTier.kt` (`TIER_1/TIER_2/TIER_3`) deleted; `LiquidityTier` extracted to its own `common/.../LiquidityTier.kt`; `LIQUID_TIERS` in `HedgeRecommendationService` updated to `{HIGH_LIQUID, LIQUID}`; V12 migration renames DB rows; `InstrumentLiquidityService.classifyTier` uses canonical names; Playwright E2E guards canonical labels in the hedge panel.

10. ✓ **`PositionPriceUpdated` event referenced but doesn't exist.** Spec chained `MarkToMarket → PositionPriceUpdated → intraday recompute`; no such event exists on the wire — both consumers (positions mark-to-market, risk-orchestrator intraday-pnl recompute) listen directly to `price.updates`. Resolved by dropping the synthetic `ensures` from `MarkToMarket` and re-keying `PriceChangeTriggersPositionChanged` to `PricePublished`. **Spec edit only.**

11. ✓ **`TradeEvent` Kafka payload drops `counterpartyId` and `strategyId`.** Restored both fields on `TradeEventMessage` as nullable, defaulted-to-null. The Trade entity already carries them and the spec already declares them on the Trade — the wire format now matches. Backwards-compatible: existing producers serialise null when unset; existing consumers ignore unknown keys; schema-test pins the round-trip and the legacy-payload backward path.

12. ✓ **EOD self-promotion bypass for AUTO_CLOSE.** `EodPromotionService.promoteToOfficialEodAutomatically` skips the `triggered_by != promoted_by` check (`EodPromotionService.kt:47-59`). Spec invariant unconditional (`risk.allium:546`). Resolved by editing the `PromoteToOfficialEod` rule to carve `AUTO_CLOSE` out of the four-eyes guard with explanatory guidance pointing back at the implementation. **Spec edit only.**

13. ⚠ **`ExpireDayOrder` rule has no implementation.** `OrderStatus.EXPIRED` enum exists; no service or scheduled job ever transitions to it. Spec `execution.allium:461-474`. **Deferred from cleanup pass (2026-05-01):** the implementation needs (a) a `TimeInForce` field on `Order` (currently absent — every order is effectively day-or-GTC-undecided), (b) a recurring scheduler with venue-specific cutoff times, and (c) a FIX cancel emitter (`OrderCancelReject` MsgType=9). All three are architectural additions requiring explicit business sign-off (which venues, which cutoffs, who owns the FIX session lifecycle). Drift-audit pass is not the right vehicle for that scope; queued for a dedicated execution-services sprint with a precursor ADR.

14. ✓ **Arrival-price staleness check is dead code via HTTP.** `position-service/.../OrderRoutes.kt:45-56` doesn't accept `arrivalPriceTimestamp`; `OrderSubmissionService.kt:65-70` only checks staleness when timestamp non-null. Fix: wire the field through the request DTO.

15. ✓ **Vol-surface diff uses nearest-neighbour instead of interpolation.** Resolved per `/quant` review: NN was wrong (introduces a discontinuous, asymmetric error that systematically pulls toward the closer knot — on a skewed surface this generates phantom diffs in the 0.5-vol-point range). Replaced with bilinear interpolation in (log K, sqrt T) space, with flat-extrapolation clamping at grid boundaries (the diff is for human review; slope-extrapolated boundary vols would be implausible). Diff functions extracted to `volatility-service/.../routes/VolSurfaceDiff.kt` for unit-testability. New `VolSurfaceDiffTest` pins eight scenarios: identical-grid, staggered-flat, parallel-shift on linear skew, between-knot interpolation, outside-grid clamping, missing-maturity sqrt-T interpolation, single-point clamp, and stable sort order. Existing `VolatilityDiffRoutesTest` updated to reflect bilinear behaviour at the route level.

16. ✓ **FIX correlation chain breaks.** `position-service/.../FIXExecutionReportProcessor.kt:111-121` builds `BookTradeCommand` without `correlationId`; downstream `TradeEvent` defaults to fresh UUID. Spec `execution.allium:328` requires `correlation_id: order.order_id`.

17. ✓ **`HandleDegradedSignals` stricter than spec.** Resolved as intentional-divergence: the `/quant` review concluded the stricter "always hold on degradation" behaviour is correct (regime-detection literature with missing observations — HMM with abstaining voters — favours holding the prior over transitioning on partial evidence; CDX gaps often coincide with credit events, which is the worst time to transition on incomplete signals). Spec edit: `HandleDegradedSignals` @guidance now states the hold-on-degraded policy explicitly with rationale, and a new first-class invariant `HoldOnDegradedInputs` pins it. **Spec edit only.**

18. ✓ **Reconciliation alert severity tiers wrong.** Spec @guidance described `WARNING < $100K < CRITICAL` — but `WARNING` is not a value of `ReconciliationBreakSeverity` (which is `normal | critical`), and the $100K threshold never matched code. Resolved by editing the `AlertOnReconciliationBreaks` and reconciliation @guidance to describe the actual single-threshold model: `normal` below $10K (recorded only, no alert), `critical` at or above $10K (recorded + alerted via `ReconciliationAlertPublisher`). A graduated escalation tier remains a future feature ticket if ops asks for it. **Spec edit only.**

19. ✓ **Hedge `staleness warning` (30min ≤ age < 2h) not implemented.** Resolved: `HedgeRecommendationService` now emits a non-blocking warning in `recommendation.message` when Greek age ≥ 30 min; hard block remains at 2 h. Per `hedge.allium:172-175`.

20. ✓ **`expire_all_pending_past_deadline` does N+1 updates instead of single UPDATE.** `risk-orchestrator/.../ExposedHedgeRecommendationRepository.kt:88-104`. Spec `hedge.allium:304-308`.

21. ✓ **Hedge accept/reject endpoints not proxied through gateway.** Resolved by adding `acceptHedgeRecommendation` / `rejectHedgeRecommendation` methods to `RiskServiceClient` + `HttpRiskServiceClient` and wiring `POST /api/v1/risk/hedge-suggest/{bookId}/{id}/accept` and `…/reject` through the gateway. Acceptance tests in `HedgeRecommendationRoutesTest` pin both happy and 404 paths for each endpoint.

22. ✓ **Pre-trade warning threshold off-by-one.** Spec text said `>` strict (`limits.allium:141`), invariant said `>=` (`limits.allium:184`). Code `LimitHierarchyService.kt:138` uses `>=`. Reconciled by editing the `CheckPositionLimit` guidance to `>=` so the spec is internally consistent and matches code. **Spec edit only.**

23. ✓ **`UpdateLimit` rule full-PUT vs partial-PATCH.** Resolved as PATCH semantics — clients should not have to echo back the whole record to toggle one attribute, which is also what every UI flow assumes. Spec ensures clauses now coalesce (`?? limit.<field>`) and a guidance note pins the wiring to `LimitRoutes.kt`. **Spec edit only.**

24. ✓ **`AlertOnReconciliationBreaks` per-break threshold check.** Resolved by changing `ReconciliationAlertPublisher.publishBreakAlert` to `(reconciliation, break)` and having `PrimeBrokerReconciliationService` iterate material breaks, firing one alert per break whose abs notional ≥ the manual-review threshold. Kafka publisher partitions by `bookId|instrumentId` so per-break alerts for the same instrument stay ordered. Severity tier values themselves are still pending the A-18 quant call.

## P2 — Aspirational/missing rules

25. ↪ `ScheduledHierarchyAggregation` — no scheduler exists. Spec `hierarchy-risk.allium:310-321`. **(triage: defer)** — on-demand aggregation already covers desk needs; scheduled run only matters when hierarchy view is the primary morning artefact, which it isn't yet.
26. ↪ `ScheduledLiquidityComputation` and `RecomputeLiquidityOnTrade` — neither wired. Spec `liquidity.allium:392-414`. **(triage: defer)** — liquidity recomputed on-demand via `ComputePositionLiquidity`; scheduled refresh becomes useful only once liquidity drives an automated decision (e.g. hedge auto-accept), which it does not today.
27. ↪ `FactorPnlAttribution` end-to-end — Python computes; Kotlin never persists/surfaces. Spec `factor-model.allium:117-129,230-248`. **(triage: implement)** — factor P&L attribution is the natural next step after factor decomposition and is already half-built; queue for a dedicated sprint after Q2 risk-budget work lands.
28. ↪ `RegimeModelConfig` entity + four-eyes governance — entirely absent. Spec `regime.allium:103-118`. **(triage: implement)** — regulatory-relevant (model governance); promote to P1 when ADR-0034 is decided so the config and the classifier behaviour ship together.
29. ↪ `data_quality_flag = stale_greeks` — never produced; 2-hour staleness check missing. Spec `risk.allium:531-533`. **(triage: implement)** — falls out of ADR-0032 (intraday Greek source). Implement the staleness flag in the same change set when A-3 is unblocked.
30. ↪ `IntradayPnlState` (frozen SOD cache) — entity doesn't exist; SOD re-read every recompute. Spec `intraday-pnl.allium:133-158`. **(triage: defer)** — DB read on every recompute is acceptable today; revisit only when intraday recompute frequency or count of books makes the round-trip a measurable cost. Linked to ADR-0032.
31. ↪ `ComputeStressedLiquidity` rule — no HTTP/Kafka trigger; only inline. Spec `liquidity.allium:316-344`. **(triage: defer)** — inline use is the only consumer; expose externally only when a downstream requester appears.
32. ↪ `ScheduledCounterpartyRisk` separate `Requested` events — code calls one inline method. Spec `counterparty-risk.allium:437-448`. **(triage: deprecate-from-spec)** — splitting into per-counterparty events adds queue and observability cost without changing observable behaviour; rewrite the rule to model the inline orchestration.
33. ↪ `CalculatePFE` and `CalculateCounterpartyExposure` separate in spec, merged in code (`CounterpartyRiskOrchestrationService.computeAndPersistPFE`). Spec `counterparty-risk.allium:236,286`. **(triage: deprecate-from-spec)** — the merge is intentional (single transaction; PFE and exposure share inputs and must be persisted together to keep snapshots consistent). Spec should describe one combined rule.
34. ↪ `IngestInstrumentLiquidity` daily batch flow — only HTTP, no Kafka subscriber. **(triage: defer)** — current HTTP ingestion serves dev seeding and one-off corrections; revisit when a real daily vendor feed is wired.
35. ↪ `RunReverseStressTest` shape — per-asset-class with vol_shock/iterations/tolerance_met (spec) vs per-instrument (code). **(triage: implement)** — spec shape is the right one for regulatory disclosure; align the code on the next reverse-stress-test feature pass and migrate persisted shape under a feature flag.
36. ↪ `ReplayResult` summary metrics — drawdown/breach count/proxy coverage missing. **(triage: implement)** — these summary metrics are required for backtest sign-off; add when the backtest reporting page lands.
37. ↪ `VerifyAuditChain` scheduled trigger — only ad-hoc verification. **(triage: implement)** — small lift, large assurance value; add once we have a generic recurring-job runner (or piggyback on the EOD scheduler).
38. ↪ `TriggerCROReport` scheduled report — no handler. **(triage: defer)** — manual trigger is currently sufficient; promote when CRO function asks for a recurring cadence.
39. ↪ `AutoClosePromoteOnRiskResult` event-bridge — synchronous in code. **(triage: deprecate-from-spec)** — synchronous in-process call is simpler, equivalent, and avoids an additional Kafka topic with its own ordering and retry semantics for what is a 1-to-1 trigger.
40. ↪ `SendOrderToFIX` event-driven trigger — synchronous in code. **(triage: deprecate-from-spec)** — order submission is inherently synchronous (the trader needs the FIX `ExecutionReport` reply on the same call); modelling it as event-driven adds latency without functional benefit.
41. ↪ `fill_retention` and `reconciliation_retention` cleanup jobs — none. **(triage: implement)** — DB hygiene + compliance retention rules require this; implement under a single lifecycle-jobs effort that also handles audit chain (#37) and any other recurring sweeps.

## P3 — Stale spec / spec drift (low-risk spec edits)

42. ✓ **Four-eyes principle spec stale.** `scenario-lifecycle.allium:124-127,448` says "current implementation does NOT enforce" but `regulatory-service/.../StressScenarioService.kt:73-75` does enforce it. Open question #1 silently resolved. **Spec edit only.**
43. ✓ **`computePnlImpact` stub bug claim outdated.** `regulatory.allium:240-243` says it's broken; `StressScenarioService.kt:183-192` now delegates to `riskOrchestratorClient.runStressTest`. **Spec edit only.**
44. ✓ **`PAGER_DUTY` note obsolete.** `alerts.allium:338-340` says PAGER_DUTY missing from `core.allium`; it's been there. **Spec edit only.**
45. ✓ **"Sequential fetch" guidance stale.** `discovery-valuation.allium:268` says sequential; `MarketDataFetcher.kt:62-69` is async/parallel up to 10. **Spec edit only.**
46. ✓ **`StressTestResultRecord` rename guidance never executed.** `scenarios.allium:226` says rename to avoid collision; code didn't. Either rename code or drop guidance. **Likely spec edit.**
47. ✓ **`HierarchyRiskSnapshot.missing_books` field.** Resolved by adding `is_partial: Boolean = false` to the persisted `HierarchyRiskSnapshot` entity (matches the existing DB column) and clarifying that `missing_books` lives only on the live `HierarchyNodeRisk` return value — historical snapshots intentionally retain only the partial flag, with per-book detail captured in service logs. The `PartialAggregationOnFailure` invariant text now describes the split. **Spec edit only.**
48. ✓ **`AuditEvent.sequence_number` not in spec.** Real column, real gap-detection route, absent from spec entity (`audit.allium:39-65`). **Spec edit.**
49. ✓ **`ReconciliationBreak.status` lifecycle absent from spec.** Full state machine in code (`ReconciliationBreak.kt:14`, `ReconciliationBreakStatus`); spec `execution.allium:42-49` stops at `severity`. **Spec edit.**
50. ✓ **`fail-open vs fail-closed` open question stale.** `execution.allium:540` lists this as open; code has fail-closed (`OrderSubmissionService.kt:113-120`). **Spec edit.**
51. ✓ **`MarketRegimeHistory` extra fields.** Code adds `id`, `confidence`, `degradedInputs`, `consecutiveObservations`, `durationMs`. Spec `regime.allium:94-101` doesn't model these. **Spec edit.**
52. ✓ **`IntradayPnlSnapshot` extra fields `missingFxRates` and `dataQualityWarning`.** Real fields, undocumented. **Spec edit.**
53. ✓ **`DailyRiskSnapshot` extra fields `varContribution`/`esContribution`/`sodVol`/`sodRate`.** Real columns, undocumented. **Spec edit.**
54. ✓ **`FactorDecompositionSnapshot.concentration_warning`.** Real field, undocumented. **Spec edit.**
55. ↪ **`InstrumentLiquidity` redundant enum** — see P1 #9; spec collapses, code splits. **(triage: implement)** — closes automatically when A-9 lands; no separate work.
56. ↪ **`auto_close_time` env-var override** — spec mentions; verify wiring exists in `Application.kt`. **(triage: implement)** — small verification ticket; if the wiring is missing, it is a 5-line config read; if present, just confirm and tick off.

## P4 — Type/nullability/cosmetic

57. ✓ `SodBaseline.source_job_id` String non-null (spec) vs UUID? (code).
58. ✓ `SavedScenario.description` nullability mismatch.
59. ✓ `SavedScenario.correlation_override` and `shocks` types disagree with JSON-string storage.
60. ✓ `StressTestResult.var_impact` Decimal? vs Double?.
61. ✓ `InstrumentFactorLoading.factor` enum (spec) vs String (code).
62. ✓ `FxRate.as_of` vs `updated_at` field naming.
63. ✓ `NettingSetSummary` (spec) vs `NettingSetExposure` (code) value-type naming.
64. ✓ `RegimeState.degraded_inputs` default differs.
65. ✓ Counterparty exposure `Decimal` (spec) vs `Double` (code) — pervasive.
66. ✓ `LiquidityRiskSnapshot.adv_data_as_of` nullability mismatch.
67. ✓ `Counterparty.sector` nullable in orchestrator DTO but non-null at source. Resolved by tightening `CounterpartyDto.sector` to non-null `String` (matching `reference-data-service/.../Counterparty.kt` and the `CounterpartyResponse` wire contract), removing the three `?: ""` fallbacks in `CounterpartyRiskOrchestrationService.kt` (lines 152, 224, 261), and adding `sector = "FINANCIALS"` to the two test fixtures in `SaCcrRoutesAcceptanceTest.kt` that previously omitted it.
68. ✓ Enum casing convention `buy/sell` (spec lowercase) vs `BUY/SELL` (code Kotlin uppercase) — uniform translation; document once.

## Specs in good shape

- `audit.allium` — hash chain matches exactly; only `sequence_number` field gap (#48).
- `scenario-lifecycle.allium` — tight; only stale four-eyes note (#42).
- `core.allium` — pure declarative; only the enum casing note (#68).
- `eod-close.allium` — promotion/supersession/mat-view retry/completeness gate match.

## Counts

- P0: 8 items
- P1: 16 items
- P2: 17 items (deferred / aspirational)
- P3: 15 items (spec edits)
- P4: 12 items (cosmetic)

Total: 68 actionable + 17 deferred ≈ 85 (the four reports flagged ~110 raw observations; consolidated dedups overlapping liquidity-tier mentions etc.).

## Recommended execution order

1. **Batch A — P3 stale-spec edits** (#42-50, #68): parallel `tend` agents, no code change, no tests needed. Lowest risk.
2. **Batch B — P3 distillation edits** (#51-54): add code-only fields back into spec via `tend`. Low risk.
3. **Batch C — P0 trivial code fixes** (#1, #7, #8, #14, #16, #20, #22 if user picks `>=`): each is a single-file change with TDD.
4. **Batch D — P0 input validation** (#4): six routes, one validation pattern. TDD.
5. **Batch E — P0 schema/data integrity** (#6): Flyway migration + service-layer guards.
6. **Batch F — P1 quant decisions** (#2, #3, #15, #17): need quant call before fixes.
7. **Batch G — P1 contract drift** (#10, #11, #12, #18, #19, #21, #23, #24): mix of code + spec.
8. **Batch H — P4 cosmetic type drift** (#57-67): mostly spec edits.
9. **Batch I — P2 aspirational triage**: per-item decision: implement, defer, or deprecate from spec.

Clear context between batches.
