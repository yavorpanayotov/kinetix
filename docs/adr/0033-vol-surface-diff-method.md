# ADR-0033: Vol-Surface Diff Method — Interpolation vs Nearest-Neighbour

## Status
Accepted (2026-05-01) — implemented in `volatility-service/.../routes/VolSurfaceDiff.kt` with bilinear interpolation in (log K, sqrt T) and flat boundary clamping. Closes audit item A-15.

## Context

Spec `market-data.allium:91-92,244-249` defines the surface diff:

> diffAgainst(other_surface) computes a point-by-point vol difference between this surface and other_surface. When strike grids differ between the two surfaces, **interpolates both to a common grid before differencing**.

Implementation in `volatility-service/.../VolatilityRoutes.kt:153-201` uses nearest-neighbour:

```kotlin
val baseVol = baseMap[strike to maturityDays]?.impliedVol
    ?: nearestVol(strike, maturityDays, baseByMaturity)
```

`nearestVol` returns the vol of the point with the same maturity whose strike is closest in absolute terms. There is no interpolation across strike or maturity.

When the two surfaces are quoted on identical grids the diff is exact (this is the common case — most ingestion pipelines normalise to the same strike ladder). When grids differ the nearest-neighbour fallback can produce visible distortion, particularly:

- Equity vol surfaces with wing differences. Grid A has strikes [80,90,100,110,120]; grid B has [85,100,115]. At strike 80 in the union, B's nearest is 85. The diff is `vol_A(80) - vol_B(85)` — comparing two genuinely different points.
- Short-dated near-ATM where skew is steepest. The error from nearest-neighbour can be 50-150 bps of vol, which is the entire diff signal traders are looking for.
- Reporting use cases: surface change reports overstate apparent moves at grid boundaries.

The existing `interpolate(strike, maturity)` function (referenced in spec at `market-data.allium:78-87` and used elsewhere in `VolatilityService`) is the standard remedy. It is bilinear in (strike, maturity) and already handles edge clamping.

## Decision (proposed)

Replace the nearest-neighbour fallback with the existing bilinear interpolator.

Concretely:

- In `computeUnionGridDiff`, when a `(strike, maturity)` is missing from one surface, call `surface.interpolate(strike, maturity)` instead of `nearestVol(...)`.
- Remove the `nearestVol` helper.
- Document the known limitation per spec line 86: "Bilinear can produce butterfly arbitrages for skewed surfaces, especially short-dated near-ATM. This is a known model limitation and must be documented in any risk model disclosure."

## Trade-offs

### Positive
- Spec-compliant; matches institutional desk convention (most vendors use bilinear or SVI for surface arithmetic).
- Eliminates the cliff at grid boundaries.
- Reuses an interpolator we already trust elsewhere — no new model risk.

### Negative
- Bilinear can produce butterfly arbitrages near ATM for skewed surfaces (already disclosed in spec line 85-87). Diff outputs are not used for repricing, so the arb only manifests as a slightly noisier diff signal — acceptable.
- Slightly more compute per missing point. The grid is small (typically <100 points per surface), and surface diff is not a hot path.

### Alternatives considered

- **SVI parameterisation diff.** Fit SVI to each surface, diff the SVI parameters, materialise on the union grid. Best academic answer; high implementation cost and SVI fitting is its own can of worms (calibration failures on illiquid wings). Reject for now — bilinear is good enough for diff, not enough headroom on the SVI investment for a reporting-only path.
- **Restrict diff to the intersection grid.** Drops grid points unique to one surface. Loses information at the wings, which is exactly where surface change is most diagnostic. Reject.
- **Keep nearest-neighbour, soften spec.** Reject — silently inserts mismatched strikes into a tool labelled "diff", and the spec's intent (interpolate to common grid) is the correct behaviour.

## Open questions for the user

1. Confirm we are happy reusing `VolSurface.interpolate()` here. It is bilinear in (strike, maturity); some desks prefer log-strike interpolation for equity vols. If we want log-strike, that is a 5-line change in the interpolator and a separate ADR about the calibration impact.
2. Should the diff output flag *interpolated* points so the UI can render them differently (e.g. dashed line)? Useful for trader trust but adds a field to `VolPointDiffDto`.

## Consequences if accepted

- Code change in `VolatilityRoutes.computeUnionGridDiff`.
- TDD: unit tests for the interpolated-vs-grid case; regression test on a real-world skewed surface pair to assert diff signal is preserved.
- No spec change — spec already describes interpolation; the audit only flagged the code drift.
