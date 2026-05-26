import pytest

pytestmark = pytest.mark.unit

from kinetix_risk.dependencies import (
    DEPENDENCIES_REGISTRY,
    MarketDataDependency,
    discover,
)
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


def _pos(
    instrument_id: str,
    asset_class: AssetClass,
    market_value: float = 100_000.0,
    currency: str = "USD",
) -> PositionRisk:
    return PositionRisk(
        instrument_id=instrument_id,
        asset_class=asset_class,
        market_value=market_value,
        currency=currency,
    )


class TestDependenciesRegistry:
    def test_every_asset_class_has_at_least_one_dependency(self):
        for ac in AssetClass:
            assert ac in DEPENDENCIES_REGISTRY, f"{ac} missing from registry"
            assert len(DEPENDENCIES_REGISTRY[ac]) >= 1, f"{ac} has no dependencies"

    def test_equity_dependencies(self):
        templates = DEPENDENCIES_REGISTRY[AssetClass.EQUITY]
        data_types = [t.data_type for t in templates]
        assert "SPOT_PRICE" in data_types
        assert "HISTORICAL_PRICES" in data_types

    def test_fixed_income_dependencies(self):
        templates = DEPENDENCIES_REGISTRY[AssetClass.FIXED_INCOME]
        data_types = [t.data_type for t in templates]
        assert "YIELD_CURVE" in data_types
        assert "CREDIT_SPREAD" in data_types

    def test_fx_dependencies(self):
        templates = DEPENDENCIES_REGISTRY[AssetClass.FX]
        data_types = [t.data_type for t in templates]
        assert "SPOT_PRICE" in data_types
        assert "FORWARD_CURVE" in data_types

    def test_commodity_dependencies(self):
        templates = DEPENDENCIES_REGISTRY[AssetClass.COMMODITY]
        data_types = [t.data_type for t in templates]
        assert "SPOT_PRICE" in data_types
        assert "FORWARD_CURVE" in data_types

    def test_derivative_dependencies(self):
        templates = DEPENDENCIES_REGISTRY[AssetClass.DERIVATIVE]
        data_types = [t.data_type for t in templates]
        assert "SPOT_PRICE" in data_types
        assert "VOLATILITY_SURFACE" in data_types
        assert "RISK_FREE_RATE" in data_types
        assert "DIVIDEND_YIELD" in data_types

    def test_derivative_has_required_flags(self):
        templates = DEPENDENCIES_REGISTRY[AssetClass.DERIVATIVE]
        by_type = {t.data_type: t for t in templates}
        assert by_type["SPOT_PRICE"].required is True
        assert by_type["VOLATILITY_SURFACE"].required is True
        assert by_type["RISK_FREE_RATE"].required is True
        assert by_type["DIVIDEND_YIELD"].required is False


class TestDiscover:
    def test_empty_positions_returns_empty(self):
        result = discover([])
        assert result == []

    def test_single_equity_position(self):
        positions = [_pos("AAPL", AssetClass.EQUITY)]
        result = discover(positions)

        data_types = [(d.data_type, d.instrument_id) for d in result]
        assert ("SPOT_PRICE", "AAPL") in data_types
        assert ("HISTORICAL_PRICES", "AAPL") in data_types

    def test_single_derivative_position(self):
        positions = [_pos("AAPL-C-250-20260620", AssetClass.DERIVATIVE)]
        result = discover(positions)

        data_types = [(d.data_type, d.instrument_id) for d in result]
        assert ("SPOT_PRICE", "AAPL-C-250-20260620") in data_types
        assert ("VOLATILITY_SURFACE", "AAPL-C-250-20260620") in data_types
        assert ("RISK_FREE_RATE", "") in data_types
        assert ("DIVIDEND_YIELD", "AAPL-C-250-20260620") in data_types

    def test_deduplication_same_instrument(self):
        positions = [
            _pos("AAPL", AssetClass.EQUITY, 100_000.0),
            _pos("AAPL", AssetClass.EQUITY, 200_000.0),
        ]
        result = discover(positions)

        spot_prices = [d for d in result if d.data_type == "SPOT_PRICE" and d.instrument_id == "AAPL"]
        assert len(spot_prices) == 1

    def test_deduplication_different_instruments(self):
        positions = [
            _pos("AAPL", AssetClass.EQUITY),
            _pos("GOOGL", AssetClass.EQUITY),
        ]
        result = discover(positions)

        spot_prices = [d for d in result if d.data_type == "SPOT_PRICE"]
        assert len(spot_prices) == 2
        instruments = {d.instrument_id for d in spot_prices}
        assert instruments == {"AAPL", "GOOGL"}

    def test_mixed_portfolio_equity_and_derivative(self):
        positions = [
            _pos("AAPL", AssetClass.EQUITY),
            _pos("AAPL-C-250-20260620", AssetClass.DERIVATIVE),
        ]
        result = discover(positions)

        data_type_instrument_pairs = {(d.data_type, d.instrument_id) for d in result}
        # Equity deps
        assert ("SPOT_PRICE", "AAPL") in data_type_instrument_pairs
        assert ("HISTORICAL_PRICES", "AAPL") in data_type_instrument_pairs
        # Derivative deps
        assert ("SPOT_PRICE", "AAPL-C-250-20260620") in data_type_instrument_pairs
        assert ("VOLATILITY_SURFACE", "AAPL-C-250-20260620") in data_type_instrument_pairs
        assert ("RISK_FREE_RATE", "") in data_type_instrument_pairs
        # Multi-asset → correlation matrix
        assert ("CORRELATION_MATRIX", "") in data_type_instrument_pairs

    def test_correlation_matrix_only_for_multiple_asset_classes(self):
        # Single asset class → no correlation matrix
        positions = [
            _pos("AAPL", AssetClass.EQUITY),
            _pos("GOOGL", AssetClass.EQUITY),
        ]
        result = discover(positions)
        data_types = [d.data_type for d in result]
        assert "CORRELATION_MATRIX" not in data_types

    def test_correlation_matrix_with_multiple_asset_classes(self):
        positions = [
            _pos("AAPL", AssetClass.EQUITY),
            _pos("GOLD", AssetClass.COMMODITY),
        ]
        result = discover(positions)
        corr = [d for d in result if d.data_type == "CORRELATION_MATRIX"]
        assert len(corr) == 1
        assert corr[0].instrument_id == ""
        # Cross-asset matrix is best-effort: the engine falls back to estimating
        # correlations from historical price series when the matrix is unavailable.
        # Marking it required=True was blocking EOD promotion for portfolios where
        # the correlation service had no entry for the requested label set.
        assert corr[0].required is False

    def test_correlation_matrix_carries_asset_class_labels(self):
        # The correlation service is keyed by labels; without them the
        # risk-orchestrator's fetcher skips the call and records MISSING.
        # The discoverer must emit the sorted asset-class names so the call is
        # actually serviceable.
        positions = [
            _pos("AAPL", AssetClass.EQUITY),
            _pos("GOLD", AssetClass.COMMODITY),
            _pos("EURUSD", AssetClass.FX),
        ]
        result = discover(positions)
        corr = next(d for d in result if d.data_type == "CORRELATION_MATRIX")
        labels = corr.parameters.get("labels", "").split(",")
        assert labels == sorted(["EQUITY", "COMMODITY", "FX"])

    def test_portfolio_level_dependencies_not_duplicated(self):
        positions = [
            _pos("OPT-1", AssetClass.DERIVATIVE),
            _pos("OPT-2", AssetClass.DERIVATIVE),
        ]
        result = discover(positions)

        risk_free_rates = [d for d in result if d.data_type == "RISK_FREE_RATE"]
        assert len(risk_free_rates) == 1
        assert risk_free_rates[0].instrument_id == ""

    def test_asset_class_set_correctly(self):
        positions = [_pos("AAPL", AssetClass.EQUITY)]
        result = discover(positions)
        for dep in result:
            assert dep.asset_class == "EQUITY"

    def test_parameters_preserved(self):
        positions = [_pos("AAPL", AssetClass.EQUITY)]
        result = discover(positions)
        hist = [d for d in result if d.data_type == "HISTORICAL_PRICES"]
        assert len(hist) == 1
        assert hist[0].parameters == {"lookbackDays": "252"}

    def test_derivative_risk_free_rate_has_currency_parameter(self):
        positions = [_pos("OPT-1", AssetClass.DERIVATIVE)]
        result = discover(positions)
        rfr = [d for d in result if d.data_type == "RISK_FREE_RATE"]
        assert len(rfr) == 1
        assert rfr[0].parameters == {"currency": "USD"}

    def test_all_asset_classes_in_single_portfolio(self):
        positions = [
            _pos("AAPL", AssetClass.EQUITY),
            _pos("TBOND-10Y", AssetClass.FIXED_INCOME),
            _pos("EURUSD", AssetClass.FX),
            _pos("GOLD", AssetClass.COMMODITY),
            _pos("AAPL-C-250", AssetClass.DERIVATIVE),
        ]
        result = discover(positions)
        data_types = {d.data_type for d in result}

        assert "SPOT_PRICE" in data_types
        assert "HISTORICAL_PRICES" in data_types
        assert "YIELD_CURVE" in data_types
        assert "CREDIT_SPREAD" in data_types
        assert "FORWARD_CURVE" in data_types
        assert "VOLATILITY_SURFACE" in data_types
        assert "RISK_FREE_RATE" in data_types
        assert "DIVIDEND_YIELD" in data_types
        assert "CORRELATION_MATRIX" in data_types

    def test_description_is_nonempty(self):
        positions = [_pos("AAPL", AssetClass.EQUITY)]
        result = discover(positions)
        for dep in result:
            assert dep.description, f"Empty description for {dep.data_type}"

    def test_fixed_income_yield_curve_is_portfolio_level(self):
        positions = [
            _pos("BOND-1", AssetClass.FIXED_INCOME),
            _pos("BOND-2", AssetClass.FIXED_INCOME),
        ]
        result = discover(positions)
        yc = [d for d in result if d.data_type == "YIELD_CURVE"]
        assert len(yc) == 1
        assert yc[0].instrument_id == ""
        assert yc[0].parameters == {"curveId": "USD"}

    def test_yield_curve_has_curve_id_from_position_currency(self):
        positions = [_pos("BOND-1", AssetClass.FIXED_INCOME, currency="EUR")]
        result = discover(positions)
        yc = [d for d in result if d.data_type == "YIELD_CURVE"]
        assert len(yc) == 1
        assert yc[0].parameters == {"curveId": "EUR"}

    def test_multi_currency_fixed_income_produces_multiple_yield_curves(self):
        positions = [
            _pos("BOND-USD", AssetClass.FIXED_INCOME, currency="USD"),
            _pos("BOND-EUR", AssetClass.FIXED_INCOME, currency="EUR"),
        ]
        result = discover(positions)
        yc = [d for d in result if d.data_type == "YIELD_CURVE"]
        assert len(yc) == 2
        params = {frozenset(d.parameters.items()) for d in yc}
        assert params == {
            frozenset({("curveId", "USD")}),
            frozenset({("curveId", "EUR")}),
        }

    def test_multi_currency_derivative_produces_multiple_risk_free_rates(self):
        positions = [
            _pos("OPT-USD", AssetClass.DERIVATIVE, currency="USD"),
            _pos("OPT-EUR", AssetClass.DERIVATIVE, currency="EUR"),
        ]
        result = discover(positions)
        rfr = [d for d in result if d.data_type == "RISK_FREE_RATE"]
        assert len(rfr) == 2
        params = {frozenset(d.parameters.items()) for d in rfr}
        assert params == {
            frozenset({("currency", "USD")}),
            frozenset({("currency", "EUR")}),
        }

    def test_fixed_income_credit_spread_is_per_instrument(self):
        positions = [
            _pos("BOND-1", AssetClass.FIXED_INCOME),
            _pos("BOND-2", AssetClass.FIXED_INCOME),
        ]
        result = discover(positions)
        cs = [d for d in result if d.data_type == "CREDIT_SPREAD"]
        assert len(cs) == 2
        instruments = {d.instrument_id for d in cs}
        assert instruments == {"BOND-1", "BOND-2"}


class TestDiscoverInstrumentType:
    """Tests for instrument-type-keyed dependency discovery."""

    def test_interest_rate_swap_gets_yield_curve_only(self):
        swap = SwapPosition(
            instrument_id="USD-SOFR-5Y",
            asset_class=AssetClass.DERIVATIVE,
            market_value=0.0,
            currency="USD",
            instrument_type="INTEREST_RATE_SWAP",
            notional=10_000_000.0,
            fixed_rate=0.035,
            maturity_date="2031-03-16",
        )
        result = discover([swap])
        data_types = {d.data_type for d in result}
        assert "YIELD_CURVE" in data_types
        assert "SPOT_PRICE" not in data_types
        assert "VOLATILITY_SURFACE" not in data_types
        assert "RISK_FREE_RATE" not in data_types
        assert "DIVIDEND_YIELD" not in data_types

    def test_interest_rate_swap_yield_curve_keyed_on_currency(self):
        swap = SwapPosition(
            instrument_id="EUR-ESTR-5Y",
            asset_class=AssetClass.DERIVATIVE,
            market_value=0.0,
            currency="EUR",
            instrument_type="INTEREST_RATE_SWAP",
            notional=10_000_000.0,
        )
        result = discover([swap])
        yc = [d for d in result if d.data_type == "YIELD_CURVE"]
        assert len(yc) == 1
        assert yc[0].parameters == {"curveId": "EUR"}

    def test_government_bond_no_credit_spread(self):
        bond = BondPosition(
            instrument_id="US10Y",
            asset_class=AssetClass.FIXED_INCOME,
            market_value=980_000.0,
            currency="USD",
            instrument_type="GOVERNMENT_BOND",
            face_value=1_000_000.0,
        )
        result = discover([bond])
        data_types = {d.data_type for d in result}
        assert "YIELD_CURVE" in data_types
        assert "CREDIT_SPREAD" not in data_types

    def test_corporate_bond_gets_yield_curve_and_credit_spread(self):
        bond = BondPosition(
            instrument_id="JPM-5Y",
            asset_class=AssetClass.FIXED_INCOME,
            market_value=490_000.0,
            currency="USD",
            instrument_type="CORPORATE_BOND",
            face_value=500_000.0,
        )
        result = discover([bond])
        data_types = {d.data_type for d in result}
        assert "YIELD_CURVE" in data_types
        assert "CREDIT_SPREAD" in data_types

    def test_equity_option_spot_keyed_on_underlying_id(self):
        opt = OptionPosition(
            instrument_id="AAPL-C-200-20260620",
            underlying_id="AAPL",
            option_type=OptionType.CALL,
            strike=200.0,
            expiry_days=90,
            spot_price=0.0,
            implied_vol=0.0,
            currency="USD",
            instrument_type="EQUITY_OPTION",
        )
        result = discover([opt])
        spot = [d for d in result if d.data_type == "SPOT_PRICE"]
        assert len(spot) == 1
        assert spot[0].instrument_id == "AAPL"  # underlying, not option id

    def test_equity_option_vol_surface_keyed_on_underlying_id(self):
        opt = OptionPosition(
            instrument_id="AAPL-C-200-20260620",
            underlying_id="AAPL",
            option_type=OptionType.CALL,
            strike=200.0,
            expiry_days=90,
            spot_price=0.0,
            implied_vol=0.0,
            currency="USD",
            instrument_type="EQUITY_OPTION",
        )
        result = discover([opt])
        vol = [d for d in result if d.data_type == "VOLATILITY_SURFACE"]
        assert len(vol) == 1
        assert vol[0].instrument_id == "AAPL"

    def test_equity_option_gets_risk_free_rate(self):
        opt = OptionPosition(
            instrument_id="AAPL-C-200",
            underlying_id="AAPL",
            option_type=OptionType.CALL,
            strike=200.0,
            expiry_days=90,
            spot_price=0.0,
            implied_vol=0.0,
            currency="USD",
            instrument_type="EQUITY_OPTION",
        )
        result = discover([opt])
        rfr = [d for d in result if d.data_type == "RISK_FREE_RATE"]
        assert len(rfr) == 1
        assert rfr[0].parameters == {"currency": "USD"}

    def test_fx_forward_gets_two_yield_curves(self):
        fwd = FxPosition(
            instrument_id="GBPUSD-3M",
            asset_class=AssetClass.FX,
            market_value=500_000.0,
            currency="USD",
            instrument_type="FX_FORWARD",
            base_currency="GBP",
            quote_currency="USD",
        )
        result = discover([fwd])
        yc = [d for d in result if d.data_type == "YIELD_CURVE"]
        assert len(yc) == 2
        params = {frozenset(d.parameters.items()) for d in yc}
        assert params == {
            frozenset({("curveId", "GBP")}),
            frozenset({("curveId", "USD")}),
        }

    def test_fx_option_gets_two_yield_curves_plus_vol_surface(self):
        opt = OptionPosition(
            instrument_id="EURUSD-C-1.10",
            underlying_id="EURUSD",
            option_type=OptionType.CALL,
            strike=1.10,
            expiry_days=60,
            spot_price=0.0,
            implied_vol=0.0,
            currency="EUR",
            instrument_type="FX_OPTION",
        )
        result = discover([opt])
        data_types = {d.data_type for d in result}
        assert "SPOT_PRICE" in data_types
        assert "VOLATILITY_SURFACE" in data_types
        assert "YIELD_CURVE" in data_types
        assert "DIVIDEND_YIELD" not in data_types

    def test_commodity_future_forward_curve_is_required(self):
        fut = FuturePosition(
            instrument_id="WTI-AUG26",
            asset_class=AssetClass.COMMODITY,
            market_value=100_000.0,
            currency="USD",
            instrument_type="COMMODITY_FUTURE",
            underlying_id="WTI",
        )
        result = discover([fut])
        fwd = [d for d in result if d.data_type == "FORWARD_CURVE"]
        assert len(fwd) == 1
        assert fwd[0].required is True

    def test_commodity_option_gets_forward_curve_and_vol_surface(self):
        opt = OptionPosition(
            instrument_id="WTI-C-80",
            underlying_id="WTI",
            option_type=OptionType.CALL,
            strike=80.0,
            expiry_days=60,
            spot_price=0.0,
            implied_vol=0.0,
            currency="USD",
            instrument_type="COMMODITY_OPTION",
        )
        result = discover([opt])
        data_types = {d.data_type for d in result}
        assert "FORWARD_CURVE" in data_types
        assert "VOLATILITY_SURFACE" in data_types
        assert "RISK_FREE_RATE" in data_types

    def test_cash_equity_unchanged(self):
        pos = PositionRisk("AAPL", AssetClass.EQUITY, 100_000.0, "USD", instrument_type="CASH_EQUITY")
        result = discover([pos])
        data_types = {d.data_type for d in result}
        assert "SPOT_PRICE" in data_types
        assert "HISTORICAL_PRICES" in data_types

    def test_fx_spot_unchanged(self):
        pos = FxPosition(
            instrument_id="EURUSD",
            asset_class=AssetClass.FX,
            market_value=1_000_000.0,
            currency="USD",
            instrument_type="FX_SPOT",
            base_currency="EUR",
            quote_currency="USD",
        )
        result = discover([pos])
        data_types = {d.data_type for d in result}
        assert "SPOT_PRICE" in data_types

    def test_equity_future_gets_spot_of_underlying(self):
        fut = FuturePosition(
            instrument_id="SPX-SEP26",
            asset_class=AssetClass.EQUITY,
            market_value=250_000.0,
            currency="USD",
            instrument_type="EQUITY_FUTURE",
            underlying_id="SPX",
        )
        result = discover([fut])
        spot = [d for d in result if d.data_type == "SPOT_PRICE"]
        assert len(spot) == 1
        assert spot[0].instrument_id == "SPX"

    def test_fallback_to_asset_class_when_instrument_type_empty(self):
        pos = _pos("AAPL", AssetClass.EQUITY)
        result = discover([pos])
        data_types = {(d.data_type, d.instrument_id) for d in result}
        assert ("SPOT_PRICE", "AAPL") in data_types
        assert ("HISTORICAL_PRICES", "AAPL") in data_types

    def test_fallback_to_asset_class_when_instrument_type_unknown(self):
        pos = PositionRisk("X", AssetClass.EQUITY, 100_000.0, "USD", instrument_type="UNKNOWN_TYPE")
        result = discover([pos])
        data_types = {d.data_type for d in result}
        assert "SPOT_PRICE" in data_types

    def test_mixed_typed_and_untyped_portfolio(self):
        """Typed and untyped positions in the same portfolio both produce correct deps."""
        positions = [
            SwapPosition(
                instrument_id="USD-5Y",
                asset_class=AssetClass.DERIVATIVE,
                market_value=0.0,
                currency="USD",
                instrument_type="INTEREST_RATE_SWAP",
                notional=10_000_000.0,
            ),
            _pos("AAPL", AssetClass.EQUITY),  # untyped fallback
        ]
        result = discover(positions)
        data_types = {(d.data_type, d.instrument_id) for d in result}
        # Swap gets yield curve
        assert ("YIELD_CURVE", "") in data_types
        # Equity gets spot
        assert ("SPOT_PRICE", "AAPL") in data_types
        # Multi-asset → correlation matrix
        corr = [d for d in result if d.data_type == "CORRELATION_MATRIX"]
        assert len(corr) == 1
