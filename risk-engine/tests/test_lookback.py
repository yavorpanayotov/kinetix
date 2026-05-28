"""Lookback option tests."""

import pytest

from kinetix_risk.lookback import floating_strike_lookback_call


@pytest.mark.unit
def test_floating_lookback_call_above_intrinsic():
    """At t=0 with min == spot, a floating-strike lookback call has
    pure time-and-vol value (no intrinsic), so price > 0."""
    price = floating_strike_lookback_call(
        spot=100.0, min_so_far=100.0, time_to_expiry=0.5,
        risk_free_rate=0.03, dividend_yield=0.0, vol=0.25,
    )
    assert price > 0


@pytest.mark.unit
def test_floating_lookback_call_above_vanilla_when_min_below_spot():
    """A floating lookback with min < spot has positive intrinsic
    AND optionality — price must exceed simple S-m intrinsic."""
    spot, m = 100.0, 90.0
    price = floating_strike_lookback_call(
        spot=spot, min_so_far=m, time_to_expiry=0.5,
        risk_free_rate=0.03, dividend_yield=0.0, vol=0.25,
    )
    assert price > spot - m


@pytest.mark.unit
def test_floating_lookback_at_expiry_equals_intrinsic():
    price = floating_strike_lookback_call(
        spot=110.0, min_so_far=80.0, time_to_expiry=0.0,
        risk_free_rate=0.03, dividend_yield=0.0, vol=0.20,
    )
    assert price == 30.0
