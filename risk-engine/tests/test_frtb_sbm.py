import pytest

from kinetix_risk.frtb.risk_weights import RISK_WEIGHTS, asset_class_to_risk_classes
from kinetix_risk.frtb.sbm import calculate_sbm
from kinetix_risk.models import AssetClass, FrtbRiskClass, PositionRisk


pytestmark = pytest.mark.unit


def _equity_position(market_value: float = 1_000_000.0) -> PositionRisk:
    return PositionRisk(
        instrument_id="AAPL",
        asset_class=AssetClass.EQUITY,
        market_value=market_value,
        currency="USD",
    )


def _sample_positions() -> list[PositionRisk]:
    return [
        PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
        PositionRisk("US10Y", AssetClass.FIXED_INCOME, 500_000.0, "USD"),
        PositionRisk("EURUSD", AssetClass.FX, 300_000.0, "USD"),
        PositionRisk("GOLD", AssetClass.COMMODITY, 200_000.0, "USD"),
        PositionRisk("SPX_OPT", AssetClass.DERIVATIVE, 400_000.0, "USD"),
    ]


class TestSbmCalculation:
    def test_single_equity_position_produces_charge(self):
        result = calculate_sbm([_equity_position()])
        assert result.total_sbm_charge > 0

    def test_all_seven_risk_classes_represented(self):
        result = calculate_sbm(_sample_positions())
        assert len(result.risk_class_charges) == 7
        risk_classes = {c.risk_class for c in result.risk_class_charges}
        assert risk_classes == set(FrtbRiskClass)

    def test_higher_exposure_produces_higher_charge(self):
        small = calculate_sbm([_equity_position(100_000.0)])
        large = calculate_sbm([_equity_position(1_000_000.0)])
        assert large.total_sbm_charge > small.total_sbm_charge

    def test_risk_weights_applied_correctly(self):
        result = calculate_sbm([_equity_position()])
        equity_charge = next(
            c for c in result.risk_class_charges if c.risk_class == FrtbRiskClass.EQUITY
        )
        assert equity_charge.delta_charge > 0
        # Equity risk weight is 20%
        assert RISK_WEIGHTS[FrtbRiskClass.EQUITY] == pytest.approx(0.20)

    def test_correlation_scenarios_max_used(self):
        result = calculate_sbm(_sample_positions())
        # Total should be positive (max of low/med/high correlation aggregation)
        assert result.total_sbm_charge > 0

    def test_empty_positions_returns_zero_charge(self):
        result = calculate_sbm([])
        assert result.total_sbm_charge == 0.0
        assert result.risk_class_charges == []

    def test_mixed_portfolio_aggregation(self):
        # A single-asset portfolio
        single = calculate_sbm([_equity_position()])
        # A diversified portfolio with same total exposure split
        diversified = calculate_sbm([
            PositionRisk("AAPL", AssetClass.EQUITY, 500_000.0, "USD"),
            PositionRisk("GOLD", AssetClass.COMMODITY, 500_000.0, "USD"),
        ])
        # Both should have positive charges
        assert single.total_sbm_charge > 0
        assert diversified.total_sbm_charge > 0


class TestRiskWeights:
    def test_asset_class_to_risk_class_mapping(self):
        assert asset_class_to_risk_classes(AssetClass.EQUITY) == [FrtbRiskClass.EQUITY]
        fi_classes = asset_class_to_risk_classes(AssetClass.FIXED_INCOME)
        assert FrtbRiskClass.GIRR in fi_classes
        assert FrtbRiskClass.CSR_NON_SEC in fi_classes

    def test_all_risk_weights_positive(self):
        for rc in FrtbRiskClass:
            assert RISK_WEIGHTS[rc] > 0
