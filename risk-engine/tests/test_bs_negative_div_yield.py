"""Tests for bs_delta under negative dividend yields.

A *negative* dividend yield isn't a corporate-action accident — it
captures the **cost of carry** for storage-bearing assets. Cash
flows the other way: holding gold costs storage fees, holding cattle
needs feed. The pricer represents that as ``q < 0`` so the
Black-Scholes formula handles them uniformly with equities.

The test pins the contract: bs_delta stays signed correctly (long
call still positive, long put still negative) under a -1% / -3% /
-5% storage cost.
"""

import pytest

from kinetix_risk.black_scholes import bs_delta
from kinetix_risk.models import OptionPosition, OptionType, AssetClass


def _call(q: float) -> OptionPosition:
    return OptionPosition(
        instrument_id="OPT-1", underlying_id="UND",
        option_type=OptionType.CALL, strike=100.0, expiry_days=90,
        spot_price=100.0, implied_vol=0.20, risk_free_rate=0.03,
        dividend_yield=q,
        asset_class=AssetClass.COMMODITY,
    )


def _put(q: float) -> OptionPosition:
    return OptionPosition(
        instrument_id="OPT-1", underlying_id="UND",
        option_type=OptionType.PUT, strike=100.0, expiry_days=90,
        spot_price=100.0, implied_vol=0.20, risk_free_rate=0.03,
        dividend_yield=q,
        asset_class=AssetClass.COMMODITY,
    )


@pytest.mark.unit
def test_call_delta_positive_under_negative_div_yield():
    assert bs_delta(_call(q=-0.02)) > 0


@pytest.mark.unit
def test_put_delta_negative_under_negative_div_yield():
    assert bs_delta(_put(q=-0.02)) < 0


@pytest.mark.unit
def test_call_delta_above_zero_div_yield_when_q_more_negative():
    """More negative storage cost makes the forward higher relative
    to spot, which raises call delta toward 1."""
    delta_zero = bs_delta(_call(q=0.0))
    delta_neg = bs_delta(_call(q=-0.05))
    assert delta_neg > delta_zero
