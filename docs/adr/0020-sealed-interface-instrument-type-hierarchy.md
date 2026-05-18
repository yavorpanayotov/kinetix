# ADR-0020: Sealed Interface Instrument Type Hierarchy

## Status
Accepted

## Context
The platform initially treated all positions as generic (asset class + quantity + price). As the risk engine matured to support options pricing (Black-Scholes), bond duration, FX forwards, and swaps, each instrument type requires type-specific attributes (strike, expiry, coupon rate, notional, etc.) that a flat model cannot represent safely.

## Decision
Model instrument types as a Kotlin sealed interface hierarchy in the shared `common` module:

```kotlin
sealed interface InstrumentType {
    val instrumentTypeName: String
    fun assetClass(): AssetClass
}
```

**11 concrete subtypes** (each in its own file under `common/.../model/instrument/`):
- Equities: `CashEquity`, `EquityOption`, `EquityFuture`
- Fixed Income: `GovernmentBond`, `CorporateBond`
- Rates: `InterestRateSwap`
- FX: `FxSpot`, `FxForward`, `FxOption`
- Commodities: `CommodityFuture`, `CommodityOption`

Each subtype carries its domain-specific attributes as `@Serializable` data class properties (e.g., `EquityOption` has `strike`, `expiry`, `optionType`, `exerciseStyle`).

The hierarchy is mirrored in:
- **Proto**: `InstrumentTypeEnum` + typed attribute messages in `risk_calculation.proto`
- **Python risk engine**: Typed position subclasses (`BondPosition`, `FuturePosition`, `FxPosition`, `SwapPosition`)
- **Database**: `instrument_type` JSONB column in the reference-data-service instruments table
- **UI**: Instrument type and name columns in position/trade grids

## Applies when
- Adding a new instrument type (exotic option, structured product, new asset class).
- Adding pricing logic that branches by instrument type.
- Touching `InstrumentType`, `AssetClass`, or any subtype data class.

## Rules
- **DO** add a new `InstrumentType` subtype as its own file under `common/.../model/instrument/`. One data class per file.
- **DO** mark every subtype `@Serializable` and give it `instrumentTypeName` plus a concrete `assetClass()`.
- **DO** update **all four layers** when adding a new type — Kotlin sealed subtype, proto `InstrumentTypeEnum` + attribute message, Python `Position` subclass in `risk-engine`, UI grid rendering and filter. Single-layer changes will compile but break at runtime.
- **DO** use `when(instrumentType)` (no `else` branch) to exploit exhaustiveness. The compiler error after adding a subtype is the checklist.
- **DON'T** add a "generic" instrument type or a `Map<String, Any>` of attributes — that defeats the entire ADR.
- **DON'T** branch on `instrumentTypeName` strings. Use type checks on the sealed subtype.
- **DON'T** push instrument-type-specific data into separate tables when JSONB on the existing instruments table is sufficient.

## Consequences

### Positive
- Compile-time exhaustiveness: `when(instrumentType)` on a sealed interface forces handling all types — adding a new type produces compiler errors at every unhandled site
- Type-specific attributes are strongly typed, not stringly-typed maps
- Each type knows its asset class — no external mapping needed
- The hierarchy crosses all layers (Kotlin, Proto, Python, UI) consistently

### Negative
- Adding a new instrument type requires changes in 4 places (Kotlin, Proto, Python, UI)
- JSONB storage in the instruments table means attribute queries require JSON operators
- 11 types is already substantial — exotic instruments may need further subtypes

### Alternatives Considered
- **Flat enum + attribute map**: A single `InstrumentType` enum with `Map<String, Any>` for attributes. Simpler to add types, but no compile-time safety for type-specific attributes.
- **Inheritance hierarchy (abstract class)**: Similar to sealed interface but less flexible in Kotlin — sealed interfaces allow data classes to implement multiple interfaces.
- **Separate tables per type**: Relational purity, but impractical with 11+ types and cross-type queries.
