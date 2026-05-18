import numpy as np
import pytest

from kinetix_risk.models import (
    AssetClass, CalculationType, ConfidenceLevel, PositionRisk, VaRResult,
)
from kinetix_risk.portfolio_risk import calculate_book_var


pytestmark = pytest.mark.unit


def _multi_asset_positions() -> list[PositionRisk]:
    return [
        PositionRisk("AAPL", AssetClass.EQUITY, 500_000.0, "USD"),
        PositionRisk("UST10Y", AssetClass.FIXED_INCOME, 300_000.0, "USD"),
    ]


class TestCorrelationMatrixOverride:
    def test_default_uses_built_in_correlation(self):
        positions = _multi_asset_positions()
        result = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        assert isinstance(result, VaRResult)
        assert result.var_value > 0

    def test_custom_correlation_matrix_used(self):
        positions = _multi_asset_positions()
        # Default result
        result_default = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        # Identity correlation (zero off-diagonal) → no diversification benefit
        identity = np.eye(2)
        result_identity = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            correlation_matrix=identity,
        )
        # With default negative correlation between EQUITY and FI,
        # VaR with identity (no correlation benefit from negative corr) should differ
        assert result_identity.var_value != pytest.approx(result_default.var_value, rel=1e-4)

    def test_none_correlation_falls_back_to_default(self):
        positions = _multi_asset_positions()
        result_none = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            correlation_matrix=None,
        )
        result_default = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        assert result_none.var_value == pytest.approx(result_default.var_value, rel=1e-10)
