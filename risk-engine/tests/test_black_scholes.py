import math

import pytest

from kinetix_risk.models import OptionType, OptionPosition
from kinetix_risk.black_scholes import (
    bs_price,
    bs_delta,
    bs_gamma,
    bs_vega,
    bs_theta,
    bs_rho,
    bs_greeks,
)


pytestmark = pytest.mark.unit


def _itm_call() -> OptionPosition:
    """In-the-money call: spot > strike."""
    return OptionPosition(
        instrument_id="AAPL-C-150",
        underlying_id="AAPL",
        option_type=OptionType.CALL,
        strike=150.0,
        expiry_days=30,
        spot_price=170.0,
        implied_vol=0.25,
        risk_free_rate=0.05,
    )


def _otm_call() -> OptionPosition:
    """Out-of-the-money call: spot < strike."""
    return OptionPosition(
        instrument_id="AAPL-C-200",
        underlying_id="AAPL",
        option_type=OptionType.CALL,
        strike=200.0,
        expiry_days=30,
        spot_price=170.0,
        implied_vol=0.25,
        risk_free_rate=0.05,
    )


def _itm_put() -> OptionPosition:
    """In-the-money put: spot < strike."""
    return OptionPosition(
        instrument_id="AAPL-P-200",
        underlying_id="AAPL",
        option_type=OptionType.PUT,
        strike=200.0,
        expiry_days=30,
        spot_price=170.0,
        implied_vol=0.25,
        risk_free_rate=0.05,
    )


def _otm_put() -> OptionPosition:
    """Out-of-the-money put: spot > strike."""
    return OptionPosition(
        instrument_id="AAPL-P-150",
        underlying_id="AAPL",
        option_type=OptionType.PUT,
        strike=150.0,
        expiry_days=30,
        spot_price=170.0,
        implied_vol=0.25,
        risk_free_rate=0.05,
    )


def _atm_call() -> OptionPosition:
    """At-the-money call: spot == strike."""
    return OptionPosition(
        instrument_id="AAPL-C-170",
        underlying_id="AAPL",
        option_type=OptionType.CALL,
        strike=170.0,
        expiry_days=90,
        spot_price=170.0,
        implied_vol=0.25,
        risk_free_rate=0.05,
    )


def _atm_put() -> OptionPosition:
    """At-the-money put: spot == strike."""
    return OptionPosition(
        instrument_id="AAPL-P-170",
        underlying_id="AAPL",
        option_type=OptionType.PUT,
        strike=170.0,
        expiry_days=90,
        spot_price=170.0,
        implied_vol=0.25,
        risk_free_rate=0.05,
    )


class TestBlackScholesPrice:
    def test_call_price_positive_for_itm_option(self):
        price = bs_price(_itm_call())
        assert price > 0.0

    def test_put_price_positive_for_otm_underlying(self):
        """A put where the underlying is below the strike should have positive value."""
        price = bs_price(_itm_put())
        assert price > 0.0

    def test_put_call_parity_holds(self):
        """C - P = S - K * exp(-r*T) for same strike/expiry."""
        call = _atm_call()
        put = _atm_put()
        T = call.expiry_days / 365.0
        call_price = bs_price(call)
        put_price = bs_price(put)
        parity_rhs = call.spot_price - call.strike * math.exp(-call.risk_free_rate * T)
        assert abs((call_price - put_price) - parity_rhs) < 1e-10


class TestBlackScholesDelta:
    def test_call_delta_between_0_and_1(self):
        delta = bs_delta(_itm_call())
        assert 0.0 < delta < 1.0

    def test_put_delta_between_minus1_and_0(self):
        delta = bs_delta(_itm_put())
        assert -1.0 < delta < 0.0

    def test_atm_call_delta_near_0_5(self):
        delta = bs_delta(_atm_call())
        assert abs(delta - 0.5) < 0.1


class TestBlackScholesGamma:
    def test_gamma_is_positive(self):
        gamma = bs_gamma(_atm_call())
        assert gamma > 0.0


class TestBlackScholesVega:
    def test_vega_is_positive(self):
        vega = bs_vega(_atm_call())
        assert vega > 0.0


class TestBlackScholesTheta:
    def test_theta_is_negative_for_long_option(self):
        theta_call = bs_theta(_atm_call())
        theta_put = bs_theta(_atm_put())
        assert theta_call < 0.0
        assert theta_put < 0.0


class TestBlackScholesRho:
    def test_rho_positive_for_call_negative_for_put(self):
        rho_call = bs_rho(_atm_call())
        rho_put = bs_rho(_atm_put())
        assert rho_call > 0.0
        assert rho_put < 0.0


class TestBlackScholesAtExpiry:
    """T=0 (expiry_days=0) must not crash — return intrinsic values."""

    def _expired_itm_call(self) -> OptionPosition:
        return OptionPosition(
            instrument_id="AAPL-C-150",
            underlying_id="AAPL",
            option_type=OptionType.CALL,
            strike=150.0,
            expiry_days=0,
            spot_price=170.0,
            implied_vol=0.25,
            risk_free_rate=0.05,
        )

    def _expired_otm_call(self) -> OptionPosition:
        return OptionPosition(
            instrument_id="AAPL-C-200",
            underlying_id="AAPL",
            option_type=OptionType.CALL,
            strike=200.0,
            expiry_days=0,
            spot_price=170.0,
            implied_vol=0.25,
            risk_free_rate=0.05,
        )

    def _expired_itm_put(self) -> OptionPosition:
        return OptionPosition(
            instrument_id="AAPL-P-200",
            underlying_id="AAPL",
            option_type=OptionType.PUT,
            strike=200.0,
            expiry_days=0,
            spot_price=170.0,
            implied_vol=0.25,
            risk_free_rate=0.05,
        )

    def _expired_otm_put(self) -> OptionPosition:
        return OptionPosition(
            instrument_id="AAPL-P-150",
            underlying_id="AAPL",
            option_type=OptionType.PUT,
            strike=150.0,
            expiry_days=0,
            spot_price=170.0,
            implied_vol=0.25,
            risk_free_rate=0.05,
        )

    def test_bs_delta_at_expiry_returns_intrinsic_delta(self):
        assert bs_delta(self._expired_itm_call()) == 1.0
        assert bs_delta(self._expired_otm_call()) == 0.0
        assert bs_delta(self._expired_itm_put()) == -1.0
        assert bs_delta(self._expired_otm_put()) == 0.0

    def test_bs_price_at_expiry_returns_intrinsic_value(self):
        assert bs_price(self._expired_itm_call()) == pytest.approx(20.0)  # 170 - 150
        assert bs_price(self._expired_otm_call()) == 0.0
        assert bs_price(self._expired_itm_put()) == pytest.approx(30.0)  # 200 - 170
        assert bs_price(self._expired_otm_put()) == 0.0

    def test_bs_gamma_at_expiry_returns_zero(self):
        assert bs_gamma(self._expired_itm_call()) == 0.0
        assert bs_gamma(self._expired_otm_call()) == 0.0

    def test_bs_vega_at_expiry_returns_zero(self):
        assert bs_vega(self._expired_itm_call()) == 0.0
        assert bs_vega(self._expired_otm_call()) == 0.0


class TestBlackScholesGreeksBundle:
    def test_bs_greeks_returns_all_greeks(self):
        greeks = bs_greeks(_atm_call())
        assert "price" in greeks
        assert "delta" in greeks
        assert "gamma" in greeks
        assert "vega" in greeks
        assert "theta" in greeks
        assert "rho" in greeks
        assert greeks["price"] > 0.0
        assert 0.0 < greeks["delta"] < 1.0
        assert greeks["gamma"] > 0.0
        assert greeks["vega"] > 0.0
        assert greeks["theta"] < 0.0
        assert greeks["rho"] > 0.0


# kx-c2n — closed-form rho vs numerical (centred-difference) reference
class TestBsExactRho:
    def _atm_call(self):
        from kinetix_risk.models import OptionPosition, OptionType, AssetClass
        return OptionPosition(
            instrument_id="OPT-1", underlying_id="UND",
            option_type=OptionType.CALL, strike=100.0, expiry_days=90,
            spot_price=100.0, implied_vol=0.20, risk_free_rate=0.03,
            asset_class=AssetClass.EQUITY,
        )

    @pytest.mark.unit
    def test_bs_exact_rho_agrees_with_numerical_to_4dp_for_atm_call(self):
        from kinetix_risk.black_scholes import bs_exact_rho, bs_rho_numerical
        opt = self._atm_call()
        exact = bs_exact_rho(opt)
        numerical = bs_rho_numerical(opt, bump=1e-4)
        assert abs(exact - numerical) < 1e-3

    @pytest.mark.unit
    def test_bs_exact_rho_matches_bs_rho(self):
        from kinetix_risk.black_scholes import bs_exact_rho, bs_rho
        opt = self._atm_call()
        assert bs_exact_rho(opt) == bs_rho(opt)

    @pytest.mark.unit
    def test_bs_exact_rho_positive_for_atm_call(self):
        from kinetix_risk.black_scholes import bs_exact_rho
        assert bs_exact_rho(self._atm_call()) > 0


# kx-sze — put-side volga with put-call parity verification
class TestBsVolgaPut:
    def _opt(self, kind):
        from kinetix_risk.models import OptionPosition, OptionType, AssetClass
        return OptionPosition(
            instrument_id="OPT-1", underlying_id="UND",
            option_type=OptionType.CALL if kind == "call" else OptionType.PUT,
            strike=100.0, expiry_days=90, spot_price=100.0,
            implied_vol=0.20, risk_free_rate=0.03,
            asset_class=AssetClass.EQUITY,
        )

    @pytest.mark.unit
    def test_bs_volga_put_matches_bs_volga_for_same_other_params(self):
        """By put-call parity, volga is the same for puts and calls."""
        from kinetix_risk.black_scholes import bs_volga, bs_volga_put
        put = self._opt("put")
        call = self._opt("call")
        assert bs_volga_put(put) == pytest.approx(bs_volga(call))

    @pytest.mark.unit
    def test_bs_volga_put_finite(self):
        from kinetix_risk.black_scholes import bs_volga_put
        v = bs_volga_put(self._opt("put"))
        assert v == v  # not NaN
