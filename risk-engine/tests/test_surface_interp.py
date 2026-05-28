"""Tests for the bivariate vol-surface interpolator."""

import pytest

from kinetix_risk.surface_interp import VolSurfaceGrid, interpolate_vol


def _grid() -> VolSurfaceGrid:
    """2x2 corner grid: strikes 90/110, expiries 30d/90d.
    Vols: 0.20 / 0.22 / 0.24 / 0.26.
    """
    return VolSurfaceGrid(
        strikes=(90.0, 110.0),
        expiries_days=(30, 90),
        vols=(
            (0.20, 0.22),  # strike 90
            (0.24, 0.26),  # strike 110
        ),
    )


@pytest.mark.unit
def test_exact_knot_returns_stored_vol():
    grid = _grid()
    assert interpolate_vol(grid, 90.0, 30) == 0.20
    assert interpolate_vol(grid, 110.0, 90) == 0.26


@pytest.mark.unit
def test_midpoint_strike_30d_is_average_of_strike_endpoints():
    grid = _grid()
    # halfway between strike 90 and 110 at expiry 30: (0.20 + 0.24) / 2 = 0.22
    assert interpolate_vol(grid, 100.0, 30) == pytest.approx(0.22)


@pytest.mark.unit
def test_midpoint_expiry_strike_90_is_average_of_expiry_endpoints():
    grid = _grid()
    # halfway between expiry 30 and 90 at strike 90: (0.20 + 0.22) / 2 = 0.21
    assert interpolate_vol(grid, 90.0, 60) == pytest.approx(0.21)


@pytest.mark.unit
def test_centre_of_grid_is_average_of_four_corners():
    grid = _grid()
    expected = (0.20 + 0.22 + 0.24 + 0.26) / 4.0  # 0.23
    assert interpolate_vol(grid, 100.0, 60) == pytest.approx(expected)


@pytest.mark.unit
def test_extrapolation_clamps_to_boundary():
    grid = _grid()
    # Far below all strikes -> uses strike-90 row, 30-day clamp.
    assert interpolate_vol(grid, 50.0, 10) == 0.20
    # Far above all strikes and expiries.
    assert interpolate_vol(grid, 200.0, 1000) == 0.26


@pytest.mark.unit
def test_malformed_grid_rejected_at_construction():
    with pytest.raises(ValueError):
        VolSurfaceGrid(strikes=(), expiries_days=(30,), vols=())
    with pytest.raises(ValueError):
        VolSurfaceGrid(strikes=(90.0,), expiries_days=(30, 90), vols=((0.2,),))
