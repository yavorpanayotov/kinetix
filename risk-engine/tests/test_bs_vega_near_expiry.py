"""Tests for the near-expiry limit of Black-Scholes vega.

Vega ~ S * sqrt(T) * phi(d1), so as T -> 0 the sqrt(T) factor sends
vega to zero. The intuition is that a near-expiry option's price is
already very close to its intrinsic — there's nothing for a vol move
to do. This test pins down the limit numerically.
"""

import pytest

from kinetix_risk.black_scholes import bs_vega
from kinetix_risk.models import OptionPosition, OptionType, AssetClass


def _atm(expiry_days: int) -> OptionPosition:
    return OptionPosition(
        instrument_id="OPT-1", underlying_id="UND",
        option_type=OptionType.CALL, strike=100.0, expiry_days=expiry_days,
        spot_price=100.0, implied_vol=0.20, risk_free_rate=0.03,
        asset_class=AssetClass.EQUITY,
    )


@pytest.mark.unit
def test_vega_at_one_day_smaller_than_at_one_year():
    """The sqrt(T) factor shrinks vega by ~sqrt(365) between 1y and 1d."""
    far = bs_vega(_atm(365))
    near = bs_vega(_atm(1))
    assert near < far
    # ratio should be roughly sqrt(365) = 19.1.
    assert far / near > 15


@pytest.mark.unit
def test_vega_at_one_day_is_positive():
    """Vega is still strictly positive for any T > 0."""
    assert bs_vega(_atm(1)) > 0


@pytest.mark.unit
def test_vega_decreases_monotonically_as_expiry_shrinks():
    v_365 = bs_vega(_atm(365))
    v_90 = bs_vega(_atm(90))
    v_30 = bs_vega(_atm(30))
    v_7 = bs_vega(_atm(7))
    assert v_365 > v_90 > v_30 > v_7
