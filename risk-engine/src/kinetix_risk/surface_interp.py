"""Bivariate volatility-surface interpolator.

Implied-vol surfaces are sampled on a grid of (strike, expiry) knots
but pricers need vol at arbitrary points. Bilinear interpolation
(2D linear) gives a smooth-enough surface for risk calc and avoids
the cubic-spline pitfall of negative vols in extrapolated corners.

References
----------
Gatheral, J. (2006). *The Volatility Surface*. Wiley. Chapter 1
    covers grid-based surface representation; the practitioner
    interpolation choices balance smoothness against the risk of
    introducing arbitrage or negative-vol points.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class VolSurfaceGrid:
    """Implied-vol grid: strikes x expiries x vols."""

    strikes: tuple[float, ...]   # ascending
    expiries_days: tuple[int, ...]   # ascending
    vols: tuple[tuple[float, ...], ...]   # vols[i_strike][j_expiry]

    def __post_init__(self) -> None:
        if not self.strikes or not self.expiries_days:
            raise ValueError("VolSurfaceGrid needs at least one strike and one expiry")
        if len(self.vols) != len(self.strikes):
            raise ValueError(
                f"vols has {len(self.vols)} rows but strikes has {len(self.strikes)}",
            )
        for row in self.vols:
            if len(row) != len(self.expiries_days):
                raise ValueError("vols row length must equal len(expiries_days)")


def interpolate_vol(grid: VolSurfaceGrid, strike: float, expiry_days: int) -> float:
    """Bilinear interpolation of vol at (strike, expiry_days).

    Outside the knot range, clamps to the boundary (flat
    extrapolation) — same convention as the rates interpolators. The
    flat-extrapolation choice avoids the cubic-spline failure mode
    where a deeply OTM strike picks up a negative vol from the
    extrapolated tail.
    """
    strikes = grid.strikes
    expiries = grid.expiries_days
    i_lo, i_hi, wi = _bracket(strikes, strike)
    j_lo, j_hi, wj = _bracket(expiries, expiry_days)
    v00 = grid.vols[i_lo][j_lo]
    v01 = grid.vols[i_lo][j_hi]
    v10 = grid.vols[i_hi][j_lo]
    v11 = grid.vols[i_hi][j_hi]
    return (
        (1 - wi) * (1 - wj) * v00
        + (1 - wi) * wj * v01
        + wi * (1 - wj) * v10
        + wi * wj * v11
    )


def _bracket(axis: tuple, value: float) -> tuple[int, int, float]:
    """Return (low_index, high_index, weight) such that the value sits
    at ``low_index + weight`` along the axis (linear interpolation
    parameter in [0, 1])."""
    if value <= axis[0]:
        return 0, 0, 0.0
    if value >= axis[-1]:
        return len(axis) - 1, len(axis) - 1, 0.0
    for i in range(len(axis) - 1):
        if axis[i] <= value <= axis[i + 1]:
            span = axis[i + 1] - axis[i]
            if span == 0:
                return i, i + 1, 0.0
            return i, i + 1, (value - axis[i]) / span
    return len(axis) - 1, len(axis) - 1, 0.0
