import pytest

from kinetix_risk.frtb.rrao import calculate_rrao
from kinetix_risk.models import AssetClass, PositionRisk


pytestmark = pytest.mark.unit


class TestRraoCalculation:
    def test_derivative_generates_rrao(self):
        positions = [
            PositionRisk("SPX_OPT", AssetClass.DERIVATIVE, 1_000_000.0, "USD"),
        ]
        result = calculate_rrao(positions)
        # 1.0% of notional = 10_000
        assert result.total_rrao == pytest.approx(10_000.0)
        assert result.exotic_notional == pytest.approx(1_000_000.0)

    def test_non_derivative_no_exotic_rrao(self):
        positions = [
            PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
        ]
        result = calculate_rrao(positions)
        assert result.exotic_notional == 0.0
        # But other_notional should be set
        assert result.other_notional == pytest.approx(1_000_000.0)
        # 0.1% of notional = 1_000
        assert result.total_rrao == pytest.approx(1_000.0)

    def test_empty_positions_returns_zero(self):
        result = calculate_rrao([])
        assert result.total_rrao == 0.0
        assert result.exotic_notional == 0.0
        assert result.other_notional == 0.0
