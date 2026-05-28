"""Asian option pricing tests."""

import pytest

from kinetix_risk.asian import asian_arithmetic_call_turnbull_wakeman


@pytest.mark.unit
def test_asian_call_below_vanilla_under_carry_zero():
    """An Asian average is less volatile than the terminal, so under
    zero cost-of-carry an arithmetic Asian call is cheaper than the
    European call with otherwise identical inputs."""
    from kinetix_risk.models import OptionPosition, OptionType, AssetClass
    from kinetix_risk.black_scholes import bs_price
    asian = asian_arithmetic_call_turnbull_wakeman(
        spot=100.0, strike=100.0, time_to_expiry=1.0,
        risk_free_rate=0.0, dividend_yield=0.0, vol=0.30,
    )
    euro = bs_price(OptionPosition(
        instrument_id="OPT", underlying_id="UND",
        option_type=OptionType.CALL, strike=100.0, expiry_days=365,
        spot_price=100.0, implied_vol=0.30, risk_free_rate=0.0,
        dividend_yield=0.0, asset_class=AssetClass.EQUITY,
    ))
    assert asian < euro
    assert asian > 0


@pytest.mark.unit
def test_asian_call_zero_at_expiry():
    """At expiry an ATM Asian payoff collapses to max(S0-K, 0)."""
    price = asian_arithmetic_call_turnbull_wakeman(
        spot=100.0, strike=100.0, time_to_expiry=0.0,
        risk_free_rate=0.03, dividend_yield=0.0, vol=0.20,
    )
    assert price == 0.0
