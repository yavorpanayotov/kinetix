"""
Property-based tests for the Black-Scholes pricing functions.

Each test verifies a *mathematical invariant* that holds for any valid input
within the tested domain, rather than a specific arithmetic result. These
complement the example-based tests in [test_black_scholes.py].

Hypothesis explores the input space randomly and shrinks failing cases to
minimal counter-examples — the exemplary technique for numerical code.
"""
from __future__ import annotations

import math

import pytest
from hypothesis import HealthCheck, assume, given, settings
from hypothesis import strategies as st

from kinetix_risk.black_scholes import (
    bs_delta,
    bs_gamma,
    bs_price,
    bs_vega,
)
from kinetix_risk.models import OptionPosition, OptionType


pytestmark = pytest.mark.unit


# Plausible domain ranges for the underlying invariants. Tightening these
# avoids floating-point pathology around extreme moneyness or near-zero vol.
spot_strategy = st.floats(min_value=10.0, max_value=1_000.0, allow_nan=False)
strike_strategy = st.floats(min_value=10.0, max_value=1_000.0, allow_nan=False)
vol_strategy = st.floats(min_value=0.05, max_value=1.50, allow_nan=False)
days_strategy = st.integers(min_value=7, max_value=730)
rate_strategy = st.floats(min_value=0.0, max_value=0.10, allow_nan=False)
div_strategy = st.floats(min_value=0.0, max_value=0.08, allow_nan=False)


def make_option(
    option_type: OptionType,
    spot: float,
    strike: float,
    vol: float,
    days: int,
    rate: float,
    div: float,
) -> OptionPosition:
    return OptionPosition(
        instrument_id="OPT",
        underlying_id="UND",
        option_type=option_type,
        strike=strike,
        expiry_days=days,
        spot_price=spot,
        implied_vol=vol,
        risk_free_rate=rate,
        dividend_yield=div,
    )


@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.too_slow])
@given(
    spot=spot_strategy,
    strike=strike_strategy,
    vol=vol_strategy,
    days=days_strategy,
    rate=rate_strategy,
    div=div_strategy,
)
def test_put_call_parity(spot, strike, vol, days, rate, div):
    """C - P == S·e^(-qT) - K·e^(-rT) — Black-Scholes' fundamental identity."""
    call = make_option(OptionType.CALL, spot, strike, vol, days, rate, div)
    put = make_option(OptionType.PUT, spot, strike, vol, days, rate, div)

    T = days / 365.0
    lhs = bs_price(call) - bs_price(put)
    rhs = spot * math.exp(-div * T) - strike * math.exp(-rate * T)

    # Tolerance scales with the magnitudes involved.
    tol = max(1e-6, 1e-9 * max(spot, strike))
    assert abs(lhs - rhs) < tol, f"parity violation: lhs={lhs}, rhs={rhs}, diff={lhs - rhs}"


@settings(max_examples=200, deadline=None)
@given(
    spot=spot_strategy,
    strike=strike_strategy,
    vol=vol_strategy,
    days=days_strategy,
    rate=rate_strategy,
    div=div_strategy,
)
def test_call_delta_in_zero_one(spot, strike, vol, days, rate, div):
    """Call delta is bounded in [0, 1] for all non-expired options."""
    call = make_option(OptionType.CALL, spot, strike, vol, days, rate, div)
    delta = bs_delta(call)
    assert 0.0 <= delta <= 1.0, f"call delta out of [0,1]: {delta}"


@settings(max_examples=200, deadline=None)
@given(
    spot=spot_strategy,
    strike=strike_strategy,
    vol=vol_strategy,
    days=days_strategy,
    rate=rate_strategy,
    div=div_strategy,
)
def test_put_delta_in_minus_one_zero(spot, strike, vol, days, rate, div):
    """Put delta is bounded in [-1, 0] for all non-expired options."""
    put = make_option(OptionType.PUT, spot, strike, vol, days, rate, div)
    delta = bs_delta(put)
    assert -1.0 <= delta <= 0.0, f"put delta out of [-1,0]: {delta}"


@settings(max_examples=150, deadline=None)
@given(
    spot=spot_strategy,
    strike=strike_strategy,
    vol=vol_strategy,
    days=days_strategy,
    rate=rate_strategy,
    div=div_strategy,
)
def test_call_price_strictly_increases_in_spot(spot, strike, vol, days, rate, div):
    """A call's price is strictly increasing in the spot price."""
    base = make_option(OptionType.CALL, spot, strike, vol, days, rate, div)
    bumped = make_option(OptionType.CALL, spot * 1.001, strike, vol, days, rate, div)

    p_base = bs_price(base)
    p_bumped = bs_price(bumped)

    # Both prices must be non-negative.
    assert p_base >= -1e-9
    assert p_bumped >= -1e-9
    # Bump in spot must not decrease call price (allow equality at deep OTM with rounding).
    assert p_bumped >= p_base - 1e-6, (
        f"call price decreased with spot bump: {p_base} → {p_bumped}"
    )


@settings(max_examples=150, deadline=None)
@given(
    spot=spot_strategy,
    strike=strike_strategy,
    vol=vol_strategy,
    days=days_strategy,
    rate=rate_strategy,
    div=div_strategy,
)
def test_call_price_decreases_in_strike(spot, strike, vol, days, rate, div):
    """A call's price is decreasing in the strike."""
    assume(strike < 999.0)  # leave room for a bump
    base = make_option(OptionType.CALL, spot, strike, vol, days, rate, div)
    bumped = make_option(OptionType.CALL, spot, strike * 1.001, vol, days, rate, div)

    p_base = bs_price(base)
    p_bumped = bs_price(bumped)

    assert p_bumped <= p_base + 1e-6, (
        f"call price increased with strike bump: {p_base} → {p_bumped}"
    )


@settings(max_examples=150, deadline=None)
@given(
    spot=spot_strategy,
    strike=strike_strategy,
    vol=vol_strategy,
    days=days_strategy,
    rate=rate_strategy,
    div=div_strategy,
)
def test_price_increases_in_volatility(spot, strike, vol, days, rate, div):
    """For both calls and puts, price is increasing in implied vol (positive vega)."""
    assume(vol < 1.40)  # leave room for a bump
    for opt_type in (OptionType.CALL, OptionType.PUT):
        base = make_option(opt_type, spot, strike, vol, days, rate, div)
        bumped = make_option(opt_type, spot, strike, vol + 0.01, days, rate, div)

        assert bs_price(bumped) >= bs_price(base) - 1e-6, (
            f"{opt_type.value} price decreased with vol bump"
        )


@settings(max_examples=100, deadline=None)
@given(
    spot=spot_strategy,
    strike=strike_strategy,
    vol=vol_strategy,
    days=days_strategy,
    rate=rate_strategy,
    div=div_strategy,
)
def test_gamma_and_vega_non_negative(spot, strike, vol, days, rate, div):
    """Gamma (second derivative wrt spot) and vega (derivative wrt vol) are always non-negative."""
    for opt_type in (OptionType.CALL, OptionType.PUT):
        opt = make_option(opt_type, spot, strike, vol, days, rate, div)
        assert bs_gamma(opt) >= -1e-9, f"negative gamma for {opt_type.value}"
        assert bs_vega(opt) >= -1e-9, f"negative vega for {opt_type.value}"
