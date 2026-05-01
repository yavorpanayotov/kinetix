# ADR-0032: Greek Source for Intraday P&L Attribution

## Status
Accepted (2026-05-01) — implemented end-to-end. Phase 1 wired `IntradayPnlService` to read pricing Greeks from `SodGreekSnapshotRepository` with VaR-Greek per-instrument fallback. Phase 2 added the `CalculatePricingGreeks` gRPC RPC on `risk-engine` (Black-Scholes for options, DV01 for FI) and the `SodSnapshotService.persistPricingGreeks` hook that populates `sod_greek_snapshots` alongside the daily VaR snapshot. Closes audit item A-3.

## Context

Spec `intraday-pnl.allium:233-241` is unambiguous:

> IMPORTANT: Use PRICING sensitivities (analytical Black-Scholes Greeks, bond_dv01, swap_dv01), NOT VaR sensitivities from greeks.py.
> The VaR engine estimates sensitivities numerically for simulation; attribution requires the analytical closed-form Greeks for precision.

The `SodGreekSnapshot` entity (frozen analytical pricing Greeks captured at SOD) is already implemented:

- Domain model: `risk-orchestrator/.../model/SodGreekSnapshot.kt`
- Repository: `risk-orchestrator/.../persistence/SodGreekSnapshotRepository.kt` + Exposed implementation
- Wired in `Application.kt:362-363, 526` and used by `PnlComputationService.kt:32, 71-84` (the EOD attribution path).

`PnlComputationService.greekOrFallback` already implements the right pattern: prefer pricing Greek; fall back to VaR Greek if absent; flag `PRICE_ONLY` quality on the attribution.

The drift is `IntradayPnlService.kt:170-200`:

```kotlin
delta = BigDecimal.valueOf(sod.delta ?: 0.0),    // sod is DailyRiskSnapshot — VaR Greek
gamma = BigDecimal.valueOf(sod.gamma ?: 0.0),
vega  = BigDecimal.valueOf(sod.vega  ?: 0.0),
theta = BigDecimal.valueOf(sod.theta ?: 0.0),
rho   = BigDecimal.valueOf(sod.rho   ?: 0.0),
```

VaR Greeks are bump-and-reprice aggregates over the simulation set. Pricing Greeks are closed-form partials. The Taylor expansion `delta_pnl = sod_delta * price_change` is correct only for closed-form `sod_delta`.

Result: intraday P&L attribution numbers are arithmetically wrong at the magnitudes traders care about. Vega in particular can differ by 20-40% between the two sources because VaR vega is computed from finite differences against the simulated vol shock distribution.

## Decision (proposed)

Wire `SodGreekSnapshotRepository` into `IntradayPnlService` and reuse the `greekOrFallback` pattern from `PnlComputationService`.

Concretely:

- Add `sodGreekSnapshotRepository: SodGreekSnapshotRepository?` constructor parameter (nullable for backwards compatibility, matches `PnlComputationService` signature).
- In `recompute()` / wherever the SOD lookup happens, fetch pricing Greeks alongside the existing `DailyRiskSnapshot` query.
- Refactor `buildAttributionInputs` to accept `Map<InstrumentId, SodGreekSnapshot>` and apply `greekOrFallback`.
- Promote `greekOrFallback` and `PositionPnlInput` to a shared location (`risk-orchestrator/.../service/PnlAttributionInputs.kt`?) so both services use one definition.
- Carry the `dataQualityFlag` (PRICING / PRICE_ONLY / FALLBACK) onto the published `IntradayPnlSnapshot`.

## Trade-offs

### Positive
- Spec compliance achieved with no new infrastructure — the entity, table, and repository all exist.
- Brings intraday and EOD attribution onto the same code path; reduces drift surface.
- Cross-Greeks (`vanna`, `volga`, `charm`) become available intraday "for free" since `SodGreekSnapshot` carries them.

### Negative
- Adds an additional DB read on every intraday recompute. Mitigation: cached in-process per `IntradayPnlState` per `intraday-pnl.allium:120-123` ("Holds frozen SOD pricing Greeks ... in memory"). The current code re-reads `DailyRiskSnapshot` every recompute too — net wash for now. **Eventually** we should implement `IntradayPnlState` (audit item #30).
- `SodGreekSnapshot` is only populated when the SOD pipeline ran with the closed-form Greeks path. Books with VaR-only SOD will continue to fall back to VaR Greeks via `greekOrFallback`, with `PRICE_ONLY` quality flag — this is the current `PnlComputationService` behaviour, so consistent.

### Alternatives considered

- **Accept VaR Greeks as approximation; soften spec.** Rejected: spec language is "MUST not" and the precision gap on vega/gamma is material. Soft-pedalling the spec would also undo the work that established `SodGreekSnapshot`.
- **Extract a shared `PositionPnlInputBuilder` first; refactor both services in one pass.** Tempting but increases blast radius. Recommend wiring intraday now, extracting the shared builder later as a follow-up.

## Open questions for the user

1. Should `IntradayPnlSnapshot.dataQualityFlag` be a new field, or is it already covered by `dataQualityWarning` (audit item #52)?
2. Do we want to enforce that intraday recompute *fails* when no `SodGreekSnapshot` exists for the book/date, rather than silently falling back to VaR Greeks? Spec leans toward fail-closed but `PnlComputationService` does fall back.

## Consequences if accepted

- Code change in `IntradayPnlService` constructor + `buildAttributionInputs`.
- Application wiring update in `Application.kt`.
- Shared `greekOrFallback` extraction (small refactor of `PnlComputationService`).
- TDD: unit tests for `greekOrFallback` priority logic; integration test that an intraday recompute uses pricing Greeks when present and VaR Greeks otherwise; acceptance test asserting `dataQualityFlag` on the published snapshot.
- This closes audit item A-3; partial progress on A-30 (`IntradayPnlState`) once we cache the pricing Greeks per book.
