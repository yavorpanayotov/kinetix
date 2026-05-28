"""Tests for the quanto option pricer."""

import pytest

from kinetix_risk.quanto import quanto_call_price


@pytest.mark.unit
def test_quanto_expired_pays_intrinsic():
    p = quanto_call_price(
        spot_foreign=120.0, strike_foreign=100.0,
        time_to_expiry_years=0.0, risk_free_rate_domestic=0.03,
        risk_free_rate_foreign=0.04, dividend_yield=0.02,
        vol_asset=0.20, vol_fx=0.10, correlation_asset_fx=0.0,
    )
    assert p == 20.0


@pytest.mark.unit
def test_quanto_price_decreases_with_positive_asset_fx_correlation():
    """Positive corr between asset and FX => negative quanto drift =>
    lower expected forward => lower call price."""
    p_zero = quanto_call_price(
        100.0, 100.0, 1.0, 0.03, 0.04, 0.02, 0.20, 0.10, correlation_asset_fx=0.0,
    )
    p_pos = quanto_call_price(
        100.0, 100.0, 1.0, 0.03, 0.04, 0.02, 0.20, 0.10, correlation_asset_fx=0.5,
    )
    assert p_pos < p_zero


@pytest.mark.unit
def test_quanto_price_increases_with_negative_asset_fx_correlation():
    p_zero = quanto_call_price(100.0, 100.0, 1.0, 0.03, 0.04, 0.02, 0.20, 0.10, correlation_asset_fx=0.0)
    p_neg = quanto_call_price(100.0, 100.0, 1.0, 0.03, 0.04, 0.02, 0.20, 0.10, correlation_asset_fx=-0.5)
    assert p_neg > p_zero


@pytest.mark.unit
def test_quanto_price_positive_for_typical_atm():
    p = quanto_call_price(100.0, 100.0, 1.0, 0.03, 0.04, 0.02, 0.20, 0.10, correlation_asset_fx=0.2)
    assert p > 0


@pytest.mark.unit
def test_quanto_rejects_invalid_correlation():
    with pytest.raises(ValueError):
        quanto_call_price(100.0, 100.0, 1.0, 0.03, 0.04, 0.02, 0.20, 0.10, correlation_asset_fx=1.01)


@pytest.mark.unit
def test_quanto_rejects_non_positive_spot():
    with pytest.raises(ValueError):
        quanto_call_price(0.0, 100.0, 1.0, 0.03, 0.04, 0.02, 0.20, 0.10, correlation_asset_fx=0.0)
