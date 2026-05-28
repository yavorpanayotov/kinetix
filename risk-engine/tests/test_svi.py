"""Tests for the SVI total-variance parameterisation."""

import pytest

from kinetix_risk.svi import SviParameters, svi_implied_vol, svi_total_variance


@pytest.mark.unit
def test_svi_total_variance_at_atm_is_finite():
    p = SviParameters(a=0.04, b=0.1, rho=-0.3, m=0.0, sigma=0.1)
    w = svi_total_variance(p, log_moneyness=0.0)
    assert w > 0


@pytest.mark.unit
def test_svi_minimum_total_variance_near_m():
    """The smile vertex is near log-moneyness m; total variance is
    minimal there (or at least close to its minimum)."""
    p = SviParameters(a=0.04, b=0.1, rho=0.0, m=0.0, sigma=0.1)
    w_atm = svi_total_variance(p, 0.0)
    w_left = svi_total_variance(p, -0.5)
    w_right = svi_total_variance(p, 0.5)
    assert w_atm <= w_left
    assert w_atm <= w_right


@pytest.mark.unit
def test_svi_constructor_rejects_negative_b():
    with pytest.raises(ValueError):
        SviParameters(a=0.04, b=-0.1, rho=0.0, m=0.0, sigma=0.1)


@pytest.mark.unit
def test_svi_constructor_rejects_invalid_rho():
    with pytest.raises(ValueError):
        SviParameters(a=0.04, b=0.1, rho=1.01, m=0.0, sigma=0.1)


@pytest.mark.unit
def test_svi_constructor_rejects_non_positive_sigma():
    with pytest.raises(ValueError):
        SviParameters(a=0.04, b=0.1, rho=0.0, m=0.0, sigma=0.0)


@pytest.mark.unit
def test_svi_implied_vol_matches_sqrt_w_over_t():
    """Implied vol = sqrt(total_var / T)."""
    import math
    p = SviParameters(a=0.04, b=0.1, rho=0.0, m=0.0, sigma=0.1)
    w = svi_total_variance(p, 0.0)
    iv = svi_implied_vol(p, 0.0, time_to_expiry=1.0)
    assert iv == pytest.approx(math.sqrt(w))


@pytest.mark.unit
def test_svi_implied_vol_rejects_non_positive_expiry():
    p = SviParameters(a=0.04, b=0.1, rho=0.0, m=0.0, sigma=0.1)
    with pytest.raises(ValueError):
        svi_implied_vol(p, 0.0, time_to_expiry=0.0)
