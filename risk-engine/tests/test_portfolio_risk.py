import numpy as np
import pytest

from kinetix_risk.models import (
    AssetClass, CalculationType, ConfidenceLevel, PositionRisk, VaRResult,
)
from kinetix_risk.portfolio_risk import calculate_book_var


pytestmark = pytest.mark.unit


def make_equity_position(instrument_id: str, market_value: float) -> PositionRisk:
    return PositionRisk(instrument_id, AssetClass.EQUITY, market_value, "USD")


class TestPortfolioRiskOrchestrator:
    def test_single_equity_position_parametric(self):
        positions = [make_equity_position("AAPL", 100_000.0)]
        result = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        assert isinstance(result, VaRResult)
        assert result.var_value > 0
        assert result.expected_shortfall > result.var_value

    def test_multiple_positions_same_asset_class_aggregated(self):
        positions = [
            make_equity_position("AAPL", 60_000.0),
            make_equity_position("MSFT", 40_000.0),
        ]
        result = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        single = calculate_book_var(
            [make_equity_position("COMBINED", 100_000.0)],
            CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        assert result.var_value == pytest.approx(single.var_value, rel=1e-6)

    def test_mixed_asset_classes(self):
        positions = [
            PositionRisk("AAPL", AssetClass.EQUITY, 100_000.0, "USD"),
            PositionRisk("UST10Y", AssetClass.FIXED_INCOME, 200_000.0, "USD"),
            PositionRisk("EURUSD", AssetClass.FX, 50_000.0, "USD"),
        ]
        result = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        assert len(result.component_breakdown) == 3

    def test_historical_method_dispatched(self):
        positions = [make_equity_position("AAPL", 100_000.0)]
        result = calculate_book_var(
            positions, CalculationType.HISTORICAL, ConfidenceLevel.CL_95, 1,
        )
        assert result.var_value > 0

    def test_monte_carlo_method_dispatched(self):
        positions = [make_equity_position("AAPL", 100_000.0)]
        result = calculate_book_var(
            positions, CalculationType.MONTE_CARLO, ConfidenceLevel.CL_95, 1,
            num_simulations=5_000,
        )
        assert result.var_value > 0

    def test_empty_positions_raises(self):
        with pytest.raises(ValueError):
            calculate_book_var(
                [], CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            )

    def test_historical_var_with_actual_returns_propagated(self):
        positions = [make_equity_position("AAPL", 100_000.0)]
        rng = np.random.default_rng(55)
        returns = rng.normal(0, 0.01, size=(250, 1))

        result = calculate_book_var(
            positions,
            CalculationType.HISTORICAL,
            ConfidenceLevel.CL_95,
            1,
            historical_returns=returns,
        )

        assert result.var_value > 0
        # Cross-check against direct calculation
        portfolio_losses = -(returns[:, 0] * 100_000)
        expected_var = float(np.percentile(portfolio_losses, 95))
        assert result.var_value == pytest.approx(expected_var, rel=1e-6)
