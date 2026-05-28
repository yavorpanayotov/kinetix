"""Tests for digital (cash-or-nothing, asset-or-nothing) option pricers."""

import pytest

from kinetix_risk.digital import asset_or_nothing_call, cash_or_nothing_call


@pytest.mark.unit
def test_cash_or_nothing_expired_in_money_pays_cash():
    assert cash_or_nothing_call(110.0, 100.0, 0.0, 0.03, 0.0, 0.20, cash_payout=5.0) == 5.0


@pytest.mark.unit
def test_cash_or_nothing_expired_out_of_money_pays_zero():
    assert cash_or_nothing_call(90.0, 100.0, 0.0, 0.03, 0.0, 0.20, cash_payout=5.0) == 0.0


@pytest.mark.unit
def test_cash_or_nothing_atm_call_around_half_pv_of_payout():
    """At-the-money cash-or-nothing call has ~50% probability of
    finishing ITM; price ~ 0.5 * cash * discount."""
    import math
    p = cash_or_nothing_call(100.0, 100.0, 0.25, 0.03, 0.0, 0.20)
    # PV factor: e^(-0.03 * 0.25) ~ 0.992. Probability ~ 0.5 (slight drift bias).
    discount = math.exp(-0.03 * 0.25)
    assert 0.3 * discount <= p <= 0.6 * discount


@pytest.mark.unit
def test_asset_or_nothing_expired_in_money_pays_spot():
    assert asset_or_nothing_call(110.0, 100.0, 0.0, 0.03, 0.0, 0.20) == 110.0


@pytest.mark.unit
def test_asset_or_nothing_expired_out_of_money_pays_zero():
    assert asset_or_nothing_call(90.0, 100.0, 0.0, 0.03, 0.0, 0.20) == 0.0


@pytest.mark.unit
def test_asset_or_nothing_atm_call_below_spot():
    """ATM asset-or-nothing is worth less than spot (you only get the
    asset if it finishes ITM, ~50% probability for ATM)."""
    p = asset_or_nothing_call(100.0, 100.0, 0.25, 0.03, 0.0, 0.20)
    assert 0 < p < 100.0
