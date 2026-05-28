"""Tests for the straddle/strangle Greeks decomposition."""

import pytest

from kinetix_risk.straddle_decomp import LegGreeks, combine_straddle_legs


@pytest.mark.unit
def test_atm_straddle_has_zero_net_delta():
    """An ATM call has delta ~ 0.5, ATM put has delta ~ -0.5; net 0."""
    call = LegGreeks(delta=0.5, gamma=0.02, vega=100, theta=-5)
    put = LegGreeks(delta=-0.5, gamma=0.02, vega=100, theta=-5)
    s = combine_straddle_legs(call, put)
    assert s.delta == pytest.approx(0.0)


@pytest.mark.unit
def test_straddle_combines_gamma_additively():
    call = LegGreeks(delta=0.5, gamma=0.02, vega=100, theta=-5)
    put = LegGreeks(delta=-0.5, gamma=0.02, vega=100, theta=-5)
    assert combine_straddle_legs(call, put).gamma == pytest.approx(0.04)


@pytest.mark.unit
def test_straddle_combines_vega_additively():
    call = LegGreeks(delta=0.5, gamma=0.02, vega=100, theta=-5)
    put = LegGreeks(delta=-0.5, gamma=0.02, vega=100, theta=-5)
    assert combine_straddle_legs(call, put).vega == pytest.approx(200)


@pytest.mark.unit
def test_straddle_combines_theta_additively():
    call = LegGreeks(delta=0.5, gamma=0.02, vega=100, theta=-5)
    put = LegGreeks(delta=-0.5, gamma=0.02, vega=100, theta=-5)
    assert combine_straddle_legs(call, put).theta == pytest.approx(-10)


@pytest.mark.unit
def test_strangle_otm_legs_have_smaller_combined_vega_than_atm():
    """An OTM strangle has smaller gamma and vega than an ATM straddle
    of the same vol — the legs sit further from the spot."""
    atm_call = LegGreeks(delta=0.5, gamma=0.02, vega=100, theta=-5)
    atm_put = LegGreeks(delta=-0.5, gamma=0.02, vega=100, theta=-5)
    atm = combine_straddle_legs(atm_call, atm_put)

    otm_call = LegGreeks(delta=0.3, gamma=0.015, vega=80, theta=-4)
    otm_put = LegGreeks(delta=-0.3, gamma=0.015, vega=80, theta=-4)
    strangle = combine_straddle_legs(otm_call, otm_put)
    assert strangle.vega < atm.vega
    assert strangle.gamma < atm.gamma


@pytest.mark.unit
def test_straddle_preserves_per_leg_greeks_for_attribution():
    """The struct retains the per-leg numbers so risk attribution
    can break the position back to its sources."""
    call = LegGreeks(delta=0.5, gamma=0.02, vega=100, theta=-5)
    put = LegGreeks(delta=-0.5, gamma=0.02, vega=100, theta=-5)
    s = combine_straddle_legs(call, put)
    assert s.call_leg.delta == 0.5
    assert s.put_leg.delta == -0.5
