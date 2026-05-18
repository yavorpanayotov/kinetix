from kinetix_risk.models import (
    AssetClass, CalculationType, ConfidenceLevel, PositionRisk,
)
from kinetix_risk.valuation import calculate_valuation

import pytest

pytestmark = pytest.mark.unit


class TestDeterministicVaR:
    def test_identical_inputs_produce_identical_var(self):
        """Calling calculate_valuation twice with identical inputs and no
        explicit volatility provider should produce exactly the same VaR."""
        positions = [
            PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
            PositionRisk("UST10Y", AssetClass.FIXED_INCOME, 500_000.0, "USD"),
            PositionRisk("GOLD", AssetClass.COMMODITY, 300_000.0, "USD"),
        ]

        result_1 = calculate_valuation(
            positions=positions,
            calculation_type=CalculationType.PARAMETRIC,
            confidence_level=ConfidenceLevel.CL_95,
            time_horizon_days=1,
        )
        result_2 = calculate_valuation(
            positions=positions,
            calculation_type=CalculationType.PARAMETRIC,
            confidence_level=ConfidenceLevel.CL_95,
            time_horizon_days=1,
        )

        assert result_1.var_result is not None
        assert result_2.var_result is not None
        assert result_1.var_result.var_value == result_2.var_result.var_value, (
            "VaR should be deterministic when no explicit volatility provider is given"
        )
