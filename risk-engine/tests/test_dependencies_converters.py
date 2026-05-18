from kinetix.risk import market_data_dependencies_pb2, risk_calculation_pb2
from kinetix_risk.converters import dependencies_to_proto
from kinetix_risk.dependencies import MarketDataDependency

import pytest

pytestmark = pytest.mark.unit


class TestDependenciesToProto:
    def test_empty_list(self):
        result = dependencies_to_proto([])
        assert len(result.dependencies) == 0

    def test_single_dependency(self):
        deps = [
            MarketDataDependency(
                data_type="SPOT_PRICE",
                instrument_id="AAPL",
                asset_class="EQUITY",
                required=True,
                description="Current spot price",
            ),
        ]
        result = dependencies_to_proto(deps)
        assert len(result.dependencies) == 1

        proto_dep = result.dependencies[0]
        assert proto_dep.data_type == risk_calculation_pb2.SPOT_PRICE
        assert proto_dep.instrument_id == "AAPL"
        assert proto_dep.asset_class == "EQUITY"
        assert proto_dep.required is True
        assert proto_dep.description == "Current spot price"
        assert len(proto_dep.parameters) == 0

    def test_dependency_with_parameters(self):
        deps = [
            MarketDataDependency(
                data_type="HISTORICAL_PRICES",
                instrument_id="AAPL",
                asset_class="EQUITY",
                required=False,
                description="Historical prices",
                parameters={"lookbackDays": "252"},
            ),
        ]
        result = dependencies_to_proto(deps)

        proto_dep = result.dependencies[0]
        assert proto_dep.data_type == risk_calculation_pb2.HISTORICAL_PRICES
        assert proto_dep.required is False
        assert proto_dep.parameters["lookbackDays"] == "252"

    def test_all_market_data_types(self):
        type_names = [
            "SPOT_PRICE",
            "HISTORICAL_PRICES",
            "VOLATILITY_SURFACE",
            "YIELD_CURVE",
            "RISK_FREE_RATE",
            "DIVIDEND_YIELD",
            "CREDIT_SPREAD",
            "FORWARD_CURVE",
            "CORRELATION_MATRIX",
        ]
        expected_protos = [
            risk_calculation_pb2.SPOT_PRICE,
            risk_calculation_pb2.HISTORICAL_PRICES,
            risk_calculation_pb2.VOLATILITY_SURFACE,
            risk_calculation_pb2.YIELD_CURVE,
            risk_calculation_pb2.RISK_FREE_RATE,
            risk_calculation_pb2.DIVIDEND_YIELD,
            risk_calculation_pb2.CREDIT_SPREAD,
            risk_calculation_pb2.FORWARD_CURVE,
            risk_calculation_pb2.CORRELATION_MATRIX,
        ]
        deps = [
            MarketDataDependency(
                data_type=t,
                instrument_id="",
                asset_class="",
                required=True,
                description=f"Test {t}",
            )
            for t in type_names
        ]
        result = dependencies_to_proto(deps)
        assert len(result.dependencies) == len(type_names)
        for proto_dep, expected_type in zip(result.dependencies, expected_protos):
            assert proto_dep.data_type == expected_type

    def test_unknown_data_type_maps_to_unspecified(self):
        deps = [
            MarketDataDependency(
                data_type="UNKNOWN_TYPE",
                instrument_id="",
                asset_class="",
                required=False,
                description="Unknown",
            ),
        ]
        result = dependencies_to_proto(deps)
        assert result.dependencies[0].data_type == risk_calculation_pb2.MARKET_DATA_TYPE_UNSPECIFIED

    def test_portfolio_level_dependency(self):
        deps = [
            MarketDataDependency(
                data_type="CORRELATION_MATRIX",
                instrument_id="",
                asset_class="",
                required=True,
                description="Cross-asset correlation matrix",
            ),
        ]
        result = dependencies_to_proto(deps)

        proto_dep = result.dependencies[0]
        assert proto_dep.instrument_id == ""
        assert proto_dep.asset_class == ""

    def test_multiple_dependencies(self):
        deps = [
            MarketDataDependency(
                data_type="SPOT_PRICE",
                instrument_id="AAPL",
                asset_class="EQUITY",
                required=True,
                description="Spot price",
            ),
            MarketDataDependency(
                data_type="VOLATILITY_SURFACE",
                instrument_id="OPT-1",
                asset_class="DERIVATIVE",
                required=True,
                description="Vol surface",
            ),
            MarketDataDependency(
                data_type="RISK_FREE_RATE",
                instrument_id="",
                asset_class="DERIVATIVE",
                required=True,
                description="Risk-free rate",
                parameters={"currency": "USD"},
            ),
        ]
        result = dependencies_to_proto(deps)
        assert len(result.dependencies) == 3
        assert result.dependencies[2].parameters["currency"] == "USD"
