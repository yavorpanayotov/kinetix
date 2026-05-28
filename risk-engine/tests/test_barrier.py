"""Barrier option tests."""

import pytest

from kinetix_risk.barrier import up_and_out_call, up_and_in_call


@pytest.mark.unit
def test_up_and_out_below_vanilla():
    """Up-and-out call must be cheaper than vanilla because of the
    knock-out probability."""
    from kinetix_risk.models import OptionPosition, OptionType, AssetClass
    from kinetix_risk.black_scholes import bs_price
    out = up_and_out_call(
        spot=100.0, strike=100.0, barrier=120.0,
        time_to_expiry=0.5, risk_free_rate=0.03,
        dividend_yield=0.0, vol=0.25,
    )
    vanilla = bs_price(OptionPosition(
        instrument_id="V", underlying_id="U",
        option_type=OptionType.CALL, strike=100.0, expiry_days=int(0.5 * 365),
        spot_price=100.0, implied_vol=0.25, risk_free_rate=0.03,
        dividend_yield=0.0, asset_class=AssetClass.EQUITY,
    ))
    assert 0 < out < vanilla


@pytest.mark.unit
def test_in_plus_out_equals_vanilla():
    """Parity: knock-in + knock-out = vanilla."""
    from kinetix_risk.models import OptionPosition, OptionType, AssetClass
    from kinetix_risk.black_scholes import bs_price
    out = up_and_out_call(
        spot=100.0, strike=100.0, barrier=130.0,
        time_to_expiry=0.5, risk_free_rate=0.03,
        dividend_yield=0.0, vol=0.20,
    )
    in_ = up_and_in_call(
        spot=100.0, strike=100.0, barrier=130.0,
        time_to_expiry=0.5, risk_free_rate=0.03,
        dividend_yield=0.0, vol=0.20,
    )
    vanilla = bs_price(OptionPosition(
        instrument_id="V", underlying_id="U",
        option_type=OptionType.CALL, strike=100.0, expiry_days=int(0.5 * 365),
        spot_price=100.0, implied_vol=0.20, risk_free_rate=0.03,
        dividend_yield=0.0, asset_class=AssetClass.EQUITY,
    ))
    assert abs((in_ + out) - vanilla) < 0.5


@pytest.mark.unit
def test_rejects_barrier_below_spot_for_up_type():
    with pytest.raises(ValueError):
        up_and_out_call(
            spot=100.0, strike=100.0, barrier=80.0,
            time_to_expiry=0.5, risk_free_rate=0.03,
            dividend_yield=0.0, vol=0.20,
        )
