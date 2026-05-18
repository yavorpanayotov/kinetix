import pytest

from kinetix_risk.frtb.calculator import calculate_frtb
from kinetix_risk.models import AssetClass, PositionRisk


pytestmark = pytest.mark.unit


def _mixed_positions() -> list[PositionRisk]:
    return [
        PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
        PositionRisk("US10Y", AssetClass.FIXED_INCOME, 500_000.0, "USD"),
        PositionRisk("SPX_OPT", AssetClass.DERIVATIVE, 400_000.0, "USD"),
        PositionRisk("GOLD", AssetClass.COMMODITY, 200_000.0, "USD"),
    ]


class TestFrtbCalculator:
    def test_mixed_portfolio_all_components(self):
        result = calculate_frtb(_mixed_positions(), "port-1")
        assert result.sbm.total_sbm_charge > 0
        assert result.drc.net_drc > 0
        assert result.rrao.total_rrao > 0

    def test_total_is_sum_of_components(self):
        result = calculate_frtb(_mixed_positions(), "port-1")
        expected = result.sbm.total_sbm_charge + result.drc.net_drc + result.rrao.total_rrao
        assert result.total_capital_charge == pytest.approx(expected)

    def test_equity_only_portfolio(self):
        positions = [
            PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
        ]
        result = calculate_frtb(positions, "port-eq")
        assert result.sbm.total_sbm_charge > 0
        assert result.drc.net_drc == 0.0  # No credit-sensitive positions
        assert result.rrao.total_rrao > 0  # Other notional still applies

    def test_empty_positions_returns_zero(self):
        result = calculate_frtb([], "port-empty")
        assert result.total_capital_charge == 0.0
        assert result.sbm.total_sbm_charge == 0.0
        assert result.drc.net_drc == 0.0
        assert result.rrao.total_rrao == 0.0
