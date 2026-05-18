import pytest

from kinetix_risk.frtb.drc import calculate_drc
from kinetix_risk.models import AssetClass, PositionRisk


pytestmark = pytest.mark.unit


class TestDrcCalculation:
    def test_fixed_income_position_has_drc(self):
        positions = [
            PositionRisk("US10Y", AssetClass.FIXED_INCOME, 1_000_000.0, "USD"),
        ]
        result = calculate_drc(positions)
        assert result.net_drc > 0

    def test_equity_has_no_drc(self):
        positions = [
            PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
        ]
        result = calculate_drc(positions)
        assert result.net_drc == 0.0
        assert result.gross_jtd == 0.0

    def test_higher_default_prob_higher_charge(self):
        positions = [
            PositionRisk("BOND_A", AssetClass.FIXED_INCOME, 1_000_000.0, "USD"),
        ]
        low_dp = calculate_drc(positions, {"BOND_A": 0.003})
        high_dp = calculate_drc(positions, {"BOND_A": 0.03})
        assert high_dp.net_drc > low_dp.net_drc

    def test_lgd_applied_correctly(self):
        positions = [
            PositionRisk("BOND_A", AssetClass.FIXED_INCOME, 1_000_000.0, "USD"),
        ]
        result = calculate_drc(positions, {"BOND_A": 0.01})
        # gross_jtd = 1_000_000 * 0.01 * 0.6 = 6000
        assert result.gross_jtd == pytest.approx(6000.0)

    def test_empty_positions_returns_zero(self):
        result = calculate_drc([])
        assert result.net_drc == 0.0
        assert result.gross_jtd == 0.0
        assert result.hedge_benefit == 0.0
