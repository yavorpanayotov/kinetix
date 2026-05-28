"""Tests for bs_price() at the zero-strike call limit.

A zero-strike call always finishes in the money — the payoff is
simply ``max(S_T, 0) = S_T``. Its price under risk-neutral
expectation is the present value of the forward,
``S_0 * e^(-qT)``, which for q=0 collapses to spot. The test pins
that limit so the pricer doesn't blow up on the K=0 boundary.
"""

import math
import pytest

from kinetix_risk.black_scholes import bs_price
from kinetix_risk.models import OptionPosition, OptionType, AssetClass


def _zero_strike_call(spot: float, q: float = 0.0, T_days: int = 90) -> OptionPosition:
    # Strike at 0.01 (numerical epsilon) — log(S/K) explodes at exact zero
    # so the test pins down behaviour at a tiny but non-zero strike, which
    # is what every real-world degenerate-call case looks like.
    return OptionPosition(
        instrument_id="OPT-1", underlying_id="UND",
        option_type=OptionType.CALL, strike=0.01, expiry_days=T_days,
        spot_price=spot, implied_vol=0.20, risk_free_rate=0.03,
        dividend_yield=q,
        asset_class=AssetClass.EQUITY,
    )


@pytest.mark.unit
def test_zero_strike_call_price_close_to_spot_when_no_dividends():
    """Without dividends, S * e^(-qT) = S, so price = spot - tiny."""
    p = bs_price(_zero_strike_call(spot=100.0))
    assert p == pytest.approx(100.0, abs=0.05)


@pytest.mark.unit
def test_zero_strike_call_price_finite_for_a_range_of_spots():
    for spot in (50.0, 100.0, 200.0, 500.0):
        p = bs_price(_zero_strike_call(spot=spot))
        assert p > 0
        assert p == pytest.approx(spot, rel=0.005)


@pytest.mark.unit
def test_zero_strike_call_responds_to_dividend_yield():
    """Higher q lowers the forward; price = S * e^(-qT) < S."""
    p_zero_q = bs_price(_zero_strike_call(spot=100.0, q=0.0))
    p_high_q = bs_price(_zero_strike_call(spot=100.0, q=0.10))
    assert p_high_q < p_zero_q
