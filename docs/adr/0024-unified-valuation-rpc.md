# ADR-0024: Unified Valuation RPC

## Status
Accepted

## Context
The original gRPC contract had a single `CalculateVaR` RPC that returned VaR and expected shortfall. As the platform evolved to support Greeks (delta, gamma, vega, theta, rho), present value, and multiple calculation types, extending the original RPC became awkward — callers couldn't specify which outputs they needed, and the response lacked fields for Greeks.

## Decision
Introduce a unified `Valuate` RPC in `risk_calculation.proto` alongside the original `CalculateVaR` (deprecated but retained for backward compatibility).

**Request (`ValuationRequest`):**
- All fields from `VaRRequest` plus:
  - `repeated ValuationOutput requested_outputs` — callers specify exactly which outputs they need (`VAR`, `EXPECTED_SHORTFALL`, `GREEKS`, `PV`)
  - `int64 monte_carlo_seed` — 0 for non-deterministic, >0 for reproducible MC runs
  - `repeated MarketDataValue market_data` — typed market data (spot prices, vol surfaces, yield curves, correlation matrices, etc.) via a `oneof` value

**Response (`ValuationResponse`):**
- All fields from `VaRResponse` plus:
  - `GreeksSummary greeks` — per-asset-class delta/gamma/vega plus portfolio theta/rho
  - `repeated ValuationOutput computed_outputs` — which outputs were actually computed
  - `double pv_value` — present value when requested
  - `string model_version` — risk-engine version for audit/reproducibility
  - `int64 monte_carlo_seed` — seed used (for manifest capture)

**Market data types** (`MarketDataValue` with typed `oneof`):
- `scalar` — spot prices, risk-free rates, dividend yields, credit spreads
- `time_series` — historical price series
- `matrix` — correlation matrices, volatility surfaces
- `curve` — yield curves, forward curves

The `RiskCalculationService` gRPC service now has three RPCs:
- `CalculateVaR` (deprecated) — original request/response
- `CalculateVaRStream` — streaming variant
- `Valuate` — unified RPC with full output control

## Applies when
- Adding a new valuation output (a new Greek, a new sensitivity, a new aggregation).
- Calling the risk-engine from new orchestrator code.
- Adding a new market data type that must reach the engine.

## Rules
- **DO** call `Valuate` for all new valuation work. Treat `CalculateVaR` as deprecated and don't add new callers.
- **DO** populate `requested_outputs` with the minimum set needed. Don't request everything — VAR + GREEKS + PV can each be expensive.
- **DO** populate `monte_carlo_seed` with a non-zero value when the result must be reproducible (regulatory submissions, replay verification per ADR-0018). Seed 0 = non-deterministic.
- **DO** add new market data types via the `MarketDataValue` `oneof` (scalar/time_series/matrix/curve). Match the Python side at the same time.
- **DO** carry `model_version` from the response into the `RunManifest`.
- **DON'T** add a new top-level RPC for a specific output (`CalculatePV`, `CalculateGreeks`). Extend `requested_outputs` and `ValuationResponse` instead.
- **DON'T** invent a new market-data shape outside the `oneof`. Use `scalar`, `time_series`, `matrix`, or `curve`; if a fifth shape is genuinely required, add it to `MarketDataValue` and update both sides.
- **DON'T** call `Valuate` and ignore `computed_outputs`. The engine may decline an output if inputs are missing; check what was actually produced.

## Consequences

### Positive
- Single RPC handles all valuation outputs — no need for separate Greek or PV endpoints
- `requested_outputs` allows callers to skip expensive computations (e.g., request PV only without Monte Carlo VaR)
- Typed market data (`oneof scalar/time_series/matrix/curve`) ensures type safety at the protocol level
- Monte Carlo seed support enables deterministic, reproducible runs
- Model version in response links results to the exact engine version for audit

### Negative
- Larger proto messages — callers that only need VaR still carry Greek/PV fields (protobuf default values are zero-cost on the wire)
- Two active RPCs (`CalculateVaR` and `Valuate`) until the deprecated one is removed
- The `oneof` market data pattern requires careful handling in both Kotlin and Python

### Alternatives Considered
- **Separate RPCs per output type**: `CalculateVaR`, `CalculateGreeks`, `CalculatePV`. Simpler per call, but requires multiple round trips for combined results and duplicates position/market data transfer.
- **REST instead of gRPC**: Simpler tooling, but gRPC's binary serialization is significantly more efficient for large position and market data payloads.
- **Extend the original `VaRRequest`/`VaRResponse`**: Avoids a new RPC, but the original message names become misleading (a "VaR request" that also computes Greeks).
