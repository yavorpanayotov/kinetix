"""Tests for Black-Scholes pricing under negative risk-free rates.

The ECB deposit rate sat at -0.50% from 2019 through 2022; any
options-pricing system that breaks on r < 0 would have failed during
that period. The formula handles negatives mathematically — exp(-rT)
is still well-defined — so the test pins down that bs_price stays
finite, positive, and monotonic in spot for a -0.5% rate.
"""

import pytest

from kinetix_risk.black_scholes import bs_price
from kinetix_risk.models import OptionPosition, OptionType, AssetClass


def _call(spot: float, rate: float) -> OptionPosition:
    return OptionPosition(
        instrument_id="OPT-1",
        underlying_id="UND",
        option_type=OptionType.CALL,
        strike=100.0,
        expiry_days=90,
        spot_price=spot,
        implied_vol=0.20,
        risk_free_rate=rate,
        asset_class=AssetClass.EQUITY,
    )


@pytest.mark.unit
def test_bs_price_finite_under_negative_rate():
    p = bs_price(_call(spot=100.0, rate=-0.005))
    assert p > 0
    assert p == p  # not NaN


@pytest.mark.unit
def test_bs_price_monotonic_in_spot_under_negative_rate():
    """For a call with r = -0.5%, price should still be monotonically
    increasing in spot."""
    p_below = bs_price(_call(spot=90.0, rate=-0.005))
    p_at = bs_price(_call(spot=100.0, rate=-0.005))
    p_above = bs_price(_call(spot=110.0, rate=-0.005))
    assert p_below < p_at < p_above


@pytest.mark.unit
def test_bs_price_under_zero_rate_matches_limit_of_negative():
    """A rate of 0 sits between negative and positive — sanity check
    that the pricing is continuous across r = 0."""
    p_neg = bs_price(_call(spot=100.0, rate=-0.001))
    p_zero = bs_price(_call(spot=100.0, rate=0.0))
    p_pos = bs_price(_call(spot=100.0, rate=+0.001))
    assert p_neg < p_zero < p_pos
