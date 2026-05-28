"""Tests for the Dupire local-volatility extractor."""

import pytest

from kinetix_risk.local_vol import local_vol_from_smile


@pytest.mark.unit
def test_local_vol_flat_smile_matches_implied_vol():
    """If implied vol is the same at every grid point, local vol
    should equal it (the smile/skew terms vanish)."""
    # 3x3 grid, all 0.20.
    iv = [[0.20] * 3 for _ in range(3)]
    strikes = [80.0, 100.0, 120.0]
    expiries = [0.25, 0.5, 1.0]
    lv = local_vol_from_smile(iv, strikes, expiries, spot=100.0)
    # Boundary rows/cols are guaranteed equal to implied vol; only the
    # interior (i=1, j=1 or j=2) goes through the finite-difference
    # branch. At a flat surface, that branch should also yield 0.20.
    for i in range(3):
        for j in range(3):
            assert lv[i][j] == pytest.approx(0.20, abs=1e-9)


@pytest.mark.unit
def test_local_vol_handles_too_small_grid():
    """A 2x2 grid (not enough points for FD) falls back to implied
    vol unchanged."""
    iv = [[0.20, 0.22], [0.24, 0.26]]
    lv = local_vol_from_smile(iv, [90.0, 110.0], [0.25, 0.5], spot=100.0)
    assert lv == iv


@pytest.mark.unit
def test_local_vol_returns_grid_of_same_shape():
    iv = [[0.20] * 3 for _ in range(3)]
    lv = local_vol_from_smile(iv, [80.0, 100.0, 120.0], [0.25, 0.5, 1.0], spot=100.0)
    assert len(lv) == 3
    assert all(len(row) == 3 for row in lv)
