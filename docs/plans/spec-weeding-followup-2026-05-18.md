# Spec Weeding Follow-up — Code Bugs

**Created:** 2026-05-18
**Source:** parallel `weed` survey of all 24 Allium specs against the codebase. Spec-side updates were applied in the same sweep; this plan tracks the **code-side** divergences where the spec was found to be correct and the implementation needs to change.

Items here are independent fixes. Each is small enough to land as one commit. See `docs/plans/spec-drift-cleanup-plan.md` (2026-04-30) for the larger drift backlog — items in this file are net new since that plan was written.

## Code-side fixes

- [ ] **risk-engine FX option curve emitter.** For a USD-base option, the second yield curve dependency is emitted with `curveId = "EUR"` instead of `"USD"`. Asymmetric fallback contradicts the discovery-valuation spec and the production behaviour everywhere else.
  - File: `risk-engine/src/kinetix_risk/dependencies.py:242-256`
  - Fix: derive `quote_currency` from the underlying FX pair, not from a hardcoded fallback.

- [ ] **Audit `alert_id` dropped on persistence.** `GovernanceAuditEvent.alertId` is on the wire but `GovernanceAuditEventConsumer.toAuditEvent` does not carry it through — `audit_events` table has no `alert_id` column and `AuditHasher` does not hash it. The audit trail for ALERT_ACKNOWLEDGED / ALERT_ESCALATED / ALERT_RESOLVED cannot answer "which alert?" without parsing the free-text `details` payload.
  - Files: `audit-service/src/main/kotlin/com/kinetix/audit/kafka/GovernanceAuditEventConsumer.kt:66-86`, `audit-service/.../persistence/AuditEventsTable.kt`, `audit-service/.../service/AuditHasher.kt`
  - Fix: add `alert_id VARCHAR(64)` column (new migration), thread through `AuditEvent`/`toAuditEvent`, and add to `AuditHasher.computeHash` order (between trader_id and event_type per existing pattern).

- [ ] **`AutoResolveAlerts` never emits `ALERT_RESOLVED` audit event.** `RulesEngine.autoResolve` flips status to RESOLVED but is constructed without an audit publisher; `AuditEventType.ALERT_RESOLVED` exists in `common` but has no producer. The hash-chained audit log is silently missing every auto-resolution.
  - Files: `notification-service/src/main/kotlin/com/kinetix/notification/engine/RulesEngine.kt:110-130`, `notification-service/.../Application.kt` (construction site)
  - Fix: wire `AuditEventPublisher` into `RulesEngine`; emit on auto-resolve mirror to manual resolve.

- [ ] **`ReplayCustomDateRange` 252-day cap not enforced.** Spec stipulates `requires: days <= config.max_replay_period_days` (252); code validates only `startDate < endDate`. Opens compute-DoS path on `/historical-replay/custom`.
  - File: `regulatory-service/.../historical/HistoricalReplayService.kt:69-78`
  - Fix: reject when `ChronoUnit.DAYS.between(start, end) > 252`.

- [ ] **Liquidity hidden fraction-of-ADV classifier.** `risk-engine/src/kinetix_risk/liquidity.py:35-55,122-163` classifies positions by `fraction_of_adv` (<10%, <25%, <50%) into HIGH_LIQUID/LIQUID/SEMI_LIQUID/ILLIQUID with a hard-coded horizon-per-tier map (1/3/5/10 days). This shadows the instrument-level tier from reference-data and means the UI horizon cannot be reconciled to `default_participation_rate` config.
  - Fix: source position tier from `InstrumentLiquidity.liquidityTier`; compute `liquidation_days = abs(market_value) / (default_participation_rate * adv)` per spec.

- [ ] **`default_participation_rate` config is inert.** Declared at `liquidity.allium:161` (default 0.20) but no consumer in `liquidity.py` or `LiquidityRiskService.kt`. Coupled to the previous item.

- [ ] **`ScheduledLiquidityComputation` daily job not implemented.** Spec requires a scheduled tick after ADV ingestion; only HTTP and trade-event hooks exist.
  - File: `risk-orchestrator/.../service/LiquidityRiskService.kt`, `risk-orchestrator/.../Application.kt` scheduler section.

- [ ] **PSD repair for correlation override not implemented.** `createCorrelatedScenario` and `update` accept any `correlationOverride` JSON without checking positive-definiteness, contrary to `NonPDCorrelationRepaired` invariant.
  - File: `risk-orchestrator/.../service/StressScenarioService.kt`
  - Fix: validate PD; if not, repair via nearest-PD and surface a warning.

- [ ] **CounterpartyRisk `peak_pfe = 0` placeholder.** `CalculateCounterpartyExposure` emits `peak_pfe: 0` per netting set; MC PFE infrastructure exists in `counterparty_risk_server.py` but the mark-to-market path never merges it into the same snapshot.
  - Files: `risk-orchestrator/.../service/CounterpartyRiskOrchestrationService.kt`, `risk-engine/.../counterparty_risk_server.py`

- [ ] **`netting_set_breakdown` dropped from `CounterpartyExposureResponse`.** Domain has it; route response (`position-service/.../routes/CounterpartyRoutes.kt:12-25`) serialises only the four aggregate fields. Either expose it or document the omission as intentional and remove from the spec value type.

- [ ] **`hedge.eligible_instruments` dead asset-class filter.** `HedgeRecommendationService.kt:243` filters `liq.assetClass !in setOf("ILLIQUID")` — but `ILLIQUID` is a liquidity tier, not an asset class, so the predicate is always true. Tier filtering happens later (`classifyTier`). Remove the dead filter.

- [ ] **`hedge.hedging_eligible` fail-open.** `HedgeRecommendationService.kt:244` treats `null` as eligible. Confirm intent; either tighten to require `== true` or document in spec @guidance (done in the spec sweep as fail-open).

- [ ] **`HedgeTarget.VAR` silently falls back to delta.** `AnalyticalHedgeCalculator.kt:46,53,135` accepts `VAR` and downgrades to delta with a Phase-2 TODO. Either reject at API or surface a `data_quality_flag` on the recommendation.

- [ ] **Position-service netting_set_breakdown DTO loss.** See cross-reference above; the same fix wires the response shape end-to-end.

- [ ] **risk-orchestrator `run_label` transitions not enforced at the recorder.** `promoteToOfficialEod` will succeed from any source label (including `adhoc`, `sod`). Tighten precondition to require `PRE_CLOSE` per the spec transition graph (or accept the broader graph in the spec — captured as a spec @guidance update in the sweep).
  - File: `risk-orchestrator/.../persistence/ExposedValuationJobRecorder.kt:367-378`

- [ ] **`PromotedJobsImmutable` invariant has no guard.** `ValuationJobRecorder` does not refuse updates after `promoted_at` is set; rely on application-layer discipline. Add an UPDATE guard or DB constraint.

- [ ] **`STALE_GREEKS` never set.** Defined in `AttributionDataQuality` enum, never assigned. Time-based staleness check (locked >2h before market open) per `risk.allium:576-578` is not implemented.
  - File: `risk-orchestrator/.../service/PnlAttributionService.kt:158-165`

- [ ] **`CheckModelStaleness` not implemented.** No `MODEL_STALENESS` alert or staleness check anywhere; the rule is documented as a goal at `regime.allium:336-346` but never wired.

- [ ] **`SaveReverseStressAsScenario` not implemented.** No route or service method. Either build it or mark `@aspirational` in spec (captured in spec sweep).

- [ ] **`ADV_CONCENTRATION` pre-trade hook not wired.** Spec rule `CheckADVConcentration` references the limit framework; no pre-trade flow uses `LimitType.ADV_CONCENTRATION`.

- [ ] **Acknowledge URL guidance.** Spec previously said `POST /alerts/{id}/ack`; code uses `/acknowledge`. Spec fixed in this sweep — listed here as a cross-reference for any clients still calling the old path.

## Cross-reference

These items in `docs/plans/spec-drift-cleanup-plan.md` overlap with the present list and should be deduplicated when that plan is next worked:
- A-7 (AlertOnBudgetBreach) — now implemented end-to-end, only spec-level documentation gap remained; included in this sweep.
- A-44 (PAGER_DUTY in core.allium) — already closed; remove from drift plan.
- A-67 (Counterparty.sector nullability) — same code-side concern, distinct row.

## Out of scope for this plan

Aspirational items left unimplemented because the spec already calls them out as forward-looking:
- `IntradayPnlState` in-memory state machine (`intraday-pnl.allium`).
- Persistent factor-loading pipeline + scheduled re-estimation (`factor-model.allium`).
- Trader-level hierarchy nodes (`hierarchy-risk.allium`).
- `CRO report` PDF/CSV via regulatory-service (`hierarchy-risk.allium`).
