"""Tests for position subtype dataclass construction and backward compatibility."""

import pytest

from kinetix_risk.models import (
    AssetClass,
    BondPosition,
    FuturePosition,
    FxPosition,
    OptionPosition,
    OptionType,
    PositionRisk,
    SwapPosition,
)


@pytest.mark.unit
class TestBondPosition:
    def test_construction_with_all_fields(self):
        pos = BondPosition(
            instrument_id="US10Y",
            asset_class=AssetClass.FIXED_INCOME,
            market_value=980_000.0,
            currency="USD",
            face_value=1_000_000.0,
            coupon_rate=0.025,
            coupon_frequency=2,
            maturity_date="2036-05-15",
            issuer="US Treasury",
            credit_rating="AAA",
            seniority="SENIOR_SECURED",
            day_count_convention="ACT/ACT",
        )
        assert pos.face_value == 1_000_000.0
        assert pos.coupon_rate == 0.025
        assert pos.maturity_date == "2036-05-15"
        assert pos.issuer == "US Treasury"

    def test_inherits_from_position_risk(self):
        pos = BondPosition(
            instrument_id="US10Y",
            asset_class=AssetClass.FIXED_INCOME,
            market_value=980_000.0,
            currency="USD",
        )
        assert isinstance(pos, PositionRisk)
        assert pos.instrument_id == "US10Y"
        assert pos.market_value == 980_000.0

    def test_defaults(self):
        pos = BondPosition(
            instrument_id="X",
            asset_class=AssetClass.FIXED_INCOME,
            market_value=100.0,
            currency="USD",
        )
        assert pos.face_value == 0.0
        assert pos.credit_rating == "UNRATED"
        assert pos.seniority == "SENIOR_UNSECURED"


@pytest.mark.unit
class TestFuturePosition:
    def test_construction(self):
        pos = FuturePosition(
            instrument_id="SPX-SEP26",
            asset_class=AssetClass.EQUITY,
            market_value=250_000.0,
            currency="USD",
            underlying_id="SPX",
            expiry_date="2026-09-18",
            contract_size=50.0,
        )
        assert pos.underlying_id == "SPX"
        assert pos.contract_size == 50.0
        assert isinstance(pos, PositionRisk)

    def test_defaults(self):
        pos = FuturePosition(
            instrument_id="X",
            asset_class=AssetClass.COMMODITY,
            market_value=100.0,
            currency="USD",
        )
        assert pos.contract_size == 1.0
        assert pos.underlying_id == ""


@pytest.mark.unit
class TestFxPosition:
    def test_construction(self):
        pos = FxPosition(
            instrument_id="EURUSD",
            asset_class=AssetClass.FX,
            market_value=1_000_000.0,
            currency="USD",
            base_currency="EUR",
            quote_currency="USD",
        )
        assert pos.base_currency == "EUR"
        assert pos.quote_currency == "USD"
        assert isinstance(pos, PositionRisk)

    def test_forward_fields(self):
        pos = FxPosition(
            instrument_id="GBPUSD-3M",
            asset_class=AssetClass.FX,
            market_value=500_000.0,
            currency="USD",
            base_currency="GBP",
            quote_currency="USD",
            delivery_date="2026-06-16",
            forward_rate=1.28,
        )
        assert pos.delivery_date == "2026-06-16"
        assert pos.forward_rate == 1.28


@pytest.mark.unit
class TestSwapPosition:
    def test_construction(self):
        pos = SwapPosition(
            instrument_id="USD-SOFR-5Y",
            asset_class=AssetClass.FIXED_INCOME,
            market_value=50_000.0,
            currency="USD",
            notional=10_000_000.0,
            fixed_rate=0.035,
            float_index="SOFR",
            maturity_date="2031-03-16",
            effective_date="2026-03-16",
            pay_receive="PAY_FIXED",
        )
        assert pos.notional == 10_000_000.0
        assert pos.fixed_rate == 0.035
        assert pos.float_index == "SOFR"
        assert isinstance(pos, PositionRisk)

    def test_defaults(self):
        pos = SwapPosition(
            instrument_id="X",
            asset_class=AssetClass.FIXED_INCOME,
            market_value=100.0,
            currency="USD",
        )
        assert pos.pay_receive == "PAY_FIXED"
        assert pos.fixed_frequency == 2
        assert pos.float_frequency == 4
        assert pos.day_count_convention == "ACT/360"


@pytest.mark.unit
class TestOptionPositionNewFields:
    def test_dividend_yield_defaults_to_zero(self):
        pos = OptionPosition(
            instrument_id="AAPL-C-200",
            underlying_id="AAPL",
            option_type=OptionType.CALL,
            strike=200.0,
            expiry_days=30,
            spot_price=195.0,
            implied_vol=0.25,
        )
        assert pos.dividend_yield == 0.0
        assert pos.contract_multiplier == 1.0

    def test_with_explicit_dividend_yield(self):
        pos = OptionPosition(
            instrument_id="AAPL-C-200",
            underlying_id="AAPL",
            option_type=OptionType.CALL,
            strike=200.0,
            expiry_days=30,
            spot_price=195.0,
            implied_vol=0.25,
            dividend_yield=0.005,
            contract_multiplier=100.0,
        )
        assert pos.dividend_yield == 0.005
        assert pos.contract_multiplier == 100.0


@pytest.mark.unit
class TestPositionSubtypePassthrough:
    """Verify that all subtypes pass through VaR pipeline as PositionRisk."""

    def test_all_subtypes_have_market_value(self):
        positions = [
            PositionRisk("A", AssetClass.EQUITY, 100.0, "USD"),
            BondPosition("B", AssetClass.FIXED_INCOME, 200.0, "USD", face_value=1000.0),
            FuturePosition("C", AssetClass.COMMODITY, 300.0, "USD", underlying_id="WTI"),
            FxPosition("D", AssetClass.FX, 400.0, "USD", base_currency="EUR", quote_currency="USD"),
            SwapPosition("E", AssetClass.FIXED_INCOME, 500.0, "USD", notional=1e6),
        ]
        for pos in positions:
            assert isinstance(pos, PositionRisk)
            assert pos.market_value > 0

    def test_option_position_exposes_market_value(self):
        # OptionPosition is not a PositionRisk subclass but the VaR pipeline
        # treats every position uniformly via `.market_value`. Trade-event
        # consumers carry the position-service-reported market value through
        # this field so books containing options can be aggregated and VaR'd
        # without a TypeError on the consumer thread.
        pos = OptionPosition(
            instrument_id="AAPL-C-200",
            underlying_id="AAPL",
            option_type=OptionType.CALL,
            strike=200.0,
            expiry_days=30,
            spot_price=195.0,
            implied_vol=0.25,
            quantity=10.0,
            market_value=12_500.0,
        )
        assert pos.market_value == 12_500.0

    def test_option_position_market_value_defaults_to_zero(self):
        pos = OptionPosition(
            instrument_id="X",
            underlying_id="Y",
            option_type=OptionType.PUT,
            strike=100.0,
            expiry_days=10,
            spot_price=100.0,
            implied_vol=0.2,
        )
        assert pos.market_value == 0.0
