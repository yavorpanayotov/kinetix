"""Tests for bs_price() numerical stability on deep-in-the-money options.

For a deep-ITM call (spot >> strike) and short time to expiry, the
naive Black-Scholes evaluation can underflow on N(-d1) or N(-d2)
where both are essentially zero. The formula should still return a
finite value close to the option's intrinsic (S - K * e^(-rT)).
"""

import pytest

from kinetix_risk.black_scholes import bs_price
from kinetix_risk.models import OptionPosition, OptionType, AssetClass


def _deep_itm_call(spot: float, strike: float) -> OptionPosition:
    return OptionPosition(
        instrument_id="OPT-1", underlying_id="UND",
        option_type=OptionType.CALL, strike=strike, expiry_days=30,
        spot_price=spot, implied_vol=0.20, risk_free_rate=0.03,
        asset_class=AssetClass.EQUITY,
    )


@pytest.mark.unit
def test_deep_itm_call_price_finite():
    p = bs_price(_deep_itm_call(spot=200.0, strike=100.0))
    assert p == p  # not NaN
    assert p > 0


@pytest.mark.unit
def test_deep_itm_call_price_close_to_intrinsic():
    """A 30d deep-ITM call (S=200, K=100) should price very close to
    its present-value intrinsic S - K * e^(-rT)."""
    import math
    opt = _deep_itm_call(spot=200.0, strike=100.0)
    p = bs_price(opt)
    T = 30 / 365.0
    intrinsic_pv = 200.0 - 100.0 * math.exp(-0.03 * T)
    assert p == pytest.approx(intrinsic_pv, rel=0.01)


@pytest.mark.unit
def test_extremely_deep_itm_does_not_blow_up():
    """S=10,000 vs K=100 — N(d1)~1, N(d2)~1, price still finite."""
    p = bs_price(_deep_itm_call(spot=10_000.0, strike=100.0))
    assert p == p
    assert p > 9000.0  # close to intrinsic
