"""Tests for the Kirk's-approximation spread option pricer."""

import pytest

from kinetix_risk.spread import kirk_spread_call_price


@pytest.mark.unit
def test_kirk_expired_spread_pays_intrinsic_difference():
    """At T=0, the spread option pays max(S1 - S2 - K, 0)."""
    p = kirk_spread_call_price(
        spot1=120.0, spot2=80.0, strike=10.0,
        time_to_expiry_years=0.0, risk_free_rate=0.03,
        vol1=0.2, vol2=0.2, correlation=0.0,
    )
    assert p == 30.0


@pytest.mark.unit
def test_kirk_expired_otm_spread_pays_zero():
    p = kirk_spread_call_price(
        spot1=90.0, spot2=100.0, strike=10.0,
        time_to_expiry_years=0.0, risk_free_rate=0.03,
        vol1=0.2, vol2=0.2, correlation=0.0,
    )
    assert p == 0.0


@pytest.mark.unit
def test_kirk_price_decreases_with_correlation():
    """Higher correlation between the two assets reduces spread
    volatility, which lowers the option's price."""
    low_corr = kirk_spread_call_price(100.0, 100.0, 5.0, 1.0, 0.03, 0.20, 0.20, correlation=0.0)
    high_corr = kirk_spread_call_price(100.0, 100.0, 5.0, 1.0, 0.03, 0.20, 0.20, correlation=0.8)
    assert high_corr < low_corr


@pytest.mark.unit
def test_kirk_price_positive_for_finite_expiry():
    p = kirk_spread_call_price(100.0, 100.0, 5.0, 1.0, 0.03, 0.20, 0.20, correlation=0.3)
    assert p > 0


@pytest.mark.unit
def test_kirk_rejects_invalid_correlation():
    with pytest.raises(ValueError):
        kirk_spread_call_price(100.0, 100.0, 5.0, 1.0, 0.03, 0.20, 0.20, correlation=1.01)


@pytest.mark.unit
def test_kirk_rejects_non_positive_spot2_plus_strike():
    with pytest.raises(ValueError):
        kirk_spread_call_price(100.0, 0.0, 0.0, 1.0, 0.03, 0.20, 0.20, correlation=0.0)
