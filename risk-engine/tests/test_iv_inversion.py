"""Tests for the Newton-Raphson implied-vol inverter."""

import pytest

from kinetix_risk.iv_inversion import implied_vol


@pytest.mark.unit
def test_implied_vol_recovers_true_vol_for_atm_call():
    """Price an ATM call at sigma=0.25, invert: should recover 0.25."""
    from kinetix_risk.iv_inversion import _bs_price_inline
    true_vol = 0.25
    spot, strike, T, r = 100.0, 100.0, 0.5, 0.03
    price = _bs_price_inline(spot, strike, T, r, true_vol, 0.0, "call")
    recovered = implied_vol(price, spot, strike, T, r, option_kind="call")
    assert recovered == pytest.approx(true_vol, rel=1e-4)


@pytest.mark.unit
def test_implied_vol_recovers_true_vol_for_otm_put():
    from kinetix_risk.iv_inversion import _bs_price_inline
    true_vol = 0.30
    spot, strike, T, r = 100.0, 90.0, 0.5, 0.03
    price = _bs_price_inline(spot, strike, T, r, true_vol, 0.0, "put")
    recovered = implied_vol(price, spot, strike, T, r, option_kind="put")
    assert recovered == pytest.approx(true_vol, rel=1e-4)


@pytest.mark.unit
def test_implied_vol_rejects_zero_price():
    with pytest.raises(ValueError):
        implied_vol(0.0, 100.0, 100.0, 0.5, 0.03)


@pytest.mark.unit
def test_implied_vol_rejects_invalid_kind():
    with pytest.raises(ValueError):
        implied_vol(5.0, 100.0, 100.0, 0.5, 0.03, option_kind="quanto")


@pytest.mark.unit
def test_implied_vol_rejects_zero_expiry():
    with pytest.raises(ValueError):
        implied_vol(5.0, 100.0, 100.0, 0.0, 0.03)
