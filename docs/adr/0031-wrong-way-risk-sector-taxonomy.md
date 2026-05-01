# ADR-0031: Wrong-Way Risk Sector Taxonomy

## Status
Accepted (2026-05-01) — implemented in `WrongWayRiskSectorGroup`, `CounterpartyRiskOrchestrationService.computeWrongWayRiskFlags`, and the @guidance on `DetectWrongWayRisk` in `counterparty-risk.allium`. Closes audit item A-2.

## Context

Spec invariant `WrongWayRiskSectorMatch` (`counterparty-risk.allium:484-486`) requires:

> Wrong-way risk flags only fire when the counterparty's sector matches the position's sector; cross-sector positions are not flagged.

Rule `DetectWrongWayRisk` (`counterparty-risk.allium:344-362`) is consistent with this: it iterates trades and flags only when *counterparty sector matches the instrument's sector*.

The implementation in `risk-orchestrator/.../CounterpartyRiskOrchestrationService.kt:219-229` ignores position sector entirely:

```kotlin
private fun computeWrongWayRiskFlags(counterparty: CounterpartyDto): List<String> {
    val flags = mutableListOf<String>()
    if (counterparty.isFinancial) {
        flags.add("FINANCIAL_SECTOR_WRONG_WAY_RISK: ...")
    }
    val sector = counterparty.sector?.uppercase() ?: ""
    if (sector in setOf("SOVEREIGN", "GOVERNMENT")) {
        flags.add("SOVEREIGN_WRONG_WAY_RISK: ...")
    }
    return flags
}
```

This produces a *counterparty-quality flag*, not a wrong-way risk flag. A hedge fund counterparty whose positions are all in industrial commodities is flagged as financial-sector WWR even though there is no correlation between the counterparty's credit and the underlying exposure.

Industry convention (BCBS d325 §83-87, ISDA WWR working group): WWR is defined as a positive correlation between exposure to a counterparty and that counterparty's PD. The two recognized forms are:

- **Specific WWR (SWWR):** the underlying is a direct claim on the counterparty (e.g. CDS on the counterparty itself, or repo of own debt). Per-position by definition.
- **General WWR (GWWR):** counterparty's PD is correlated with the broad sector or asset class of its exposures. Per-position, sector-matched.

A pure counterparty-attribute flag does not meet either definition.

## Decision (proposed)

Implement strict sector-match WWR per spec, replacing the current counterparty-only heuristic.

The flag fires when the counterparty's sector matches the *position's* sector for that trade; the flag enumerates the offending instruments rather than the counterparty as a whole.

Concretely:

- Walk trades for the counterparty (already loaded in `computeAndPersistPFE`).
- For each trade, fetch instrument sector via reference-data-service.
- Emit one `WrongWayRiskFlag` per matching trade with `instrument_id`, `counterparty_sector`, `position_sector`, and `exposure_amount`.
- Aggregate counts into `wrongWayRiskFlags: List<String>` on the snapshot for backwards-compatible API surface; the per-trade detail goes onto a new `wrongWayRiskFlags: List<WrongWayRiskFlag>` field on `CounterpartyRiskSnapshot` (matches spec value type at `counterparty-risk.allium:52-58`).

## Trade-offs

### Positive
- Matches spec; matches Basel/ISDA convention.
- Eliminates false positives — e.g. an industrial corporate's exposure to a bank counterparty is no longer flagged.
- Per-trade granularity supports drill-down in the UI and CVA add-on calculations downstream.

### Negative
- Requires an additional reference-data lookup per trade. Most counterparties are small (≤100 trades); cacheable.
- Expands `CounterpartyRiskSnapshot` surface — new field needs migration.
- Live counterparty risk dashboards that depend on the current flag set will see flag counts drop sharply once misfires are removed; we should communicate this to the trading desks.

### Alternatives considered

- **Keep coarse heuristic, soften spec.** Treat any financial-sector counterparty as portfolio-wide WWR. Rejected: this is not WWR by any recognized definition. We would be misnaming a counterparty-attribute flag and would still need a real WWR signal for CVA add-ons.
- **Hybrid (sector match + pure-financial fallback).** Still emit `FINANCIAL_SECTOR_WRONG_WAY_RISK` when no position-sector data is available. Rejected: encourages reference-data laziness and produces misleading flags during data outages.

## Open questions for the user

1. Position sector data path — should we use `instrument.sector` from reference-data-service, or `assetClass` from `Position`? The former is finer (e.g. `BANKING` vs `MATERIALS`); the latter is always present.
2. Sovereign WWR — should sovereign be matched against any government-bond position, or limited to positions referencing the same sovereign issuer? Strictest reading suggests issuer-match.

## Consequences if accepted

- Code change in `CounterpartyRiskOrchestrationService.computeWrongWayRiskFlags`.
- New value-type `WrongWayRiskFlag` in `risk-orchestrator/model/`.
- Migration to add `wrong_way_risk_flags_detail` JSON column on `counterparty_risk_snapshots`.
- TDD: unit tests for sector-match logic; acceptance test for snapshot persistence; UI E2E for the new flag detail view.
