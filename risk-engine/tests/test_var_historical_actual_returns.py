import numpy as np
import pytest

from kinetix_risk.models import AssetClass, AssetClassExposure, ConfidenceLevel
from kinetix_risk.var_historical import calculate_historical_var


pytestmark = pytest.mark.unit


class TestHistoricalVaRWithActualReturns:
    """True historical VaR uses real historical return series rather than
    randomly generated normal returns."""

    def test_historical_var_uses_actual_returns_when_provided(self):
        """When historical_returns are supplied the VaR should be driven
        entirely by those observations, not by any RNG draw."""
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])

        # Construct a deterministic return series with realistic dispersion.
        # 250 days of small positive drift with a few negative outliers.
        rng = np.random.default_rng(42)
        returns = rng.normal(0.0005, 0.01, size=(250, 1))
        # Inject a known large loss at index 0
        returns[0, 0] = -0.05

        result = calculate_historical_var(
            exposures,
            ConfidenceLevel.CL_95,
            1,
            corr,
            historical_returns=returns,
        )

        # Portfolio loss for a -r return on 100k is r * 100k.
        # The VaR should reflect the empirical 95th-percentile loss.
        assert result.var_value > 0

        # Manually compute expected VaR for cross-check
        portfolio_losses = -(returns[:, 0] * 100_000)
        expected_var = float(np.percentile(portfolio_losses, 95))
        assert result.var_value == pytest.approx(expected_var, rel=1e-6)

    def test_historical_var_with_250_historical_scenarios(self):
        """250 historical daily returns produce 250 portfolio-loss scenarios."""
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])

        rng = np.random.default_rng(99)
        returns = rng.normal(0, 0.01, size=(250, 1))

        result = calculate_historical_var(
            exposures,
            ConfidenceLevel.CL_95,
            1,
            corr,
            historical_returns=returns,
        )

        assert result.var_value > 0
        assert result.expected_shortfall >= result.var_value

    def test_historical_var_fallback_to_simulated_when_no_history(self):
        """When historical_returns is None the function falls back to
        the existing simulated-returns behaviour (backward compatibility)."""
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])

        result_a = calculate_historical_var(
            exposures, ConfidenceLevel.CL_95, 1, corr, seed=42,
            historical_returns=None,
        )
        result_b = calculate_historical_var(
            exposures, ConfidenceLevel.CL_95, 1, corr, seed=42,
        )

        # Both calls should give the same result since the fallback path
        # is identical to the existing code path.
        assert result_a.var_value == pytest.approx(result_b.var_value, rel=1e-6)

    def test_historical_var_produces_different_result_than_simulated(self):
        """Actual historical returns should produce a different VaR than
        the simulated approach (unless the returns happen to be identical
        to a normal draw, which is essentially impossible)."""
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])

        # Fat-tailed returns that differ markedly from normal
        rng = np.random.default_rng(7)
        returns = rng.standard_t(df=3, size=(250, 1)) * 0.01

        historical_result = calculate_historical_var(
            exposures,
            ConfidenceLevel.CL_95,
            1,
            corr,
            historical_returns=returns,
        )

        simulated_result = calculate_historical_var(
            exposures,
            ConfidenceLevel.CL_95,
            1,
            corr,
            seed=42,
        )

        # The two numbers should differ because the underlying return
        # distributions are different.
        assert historical_result.var_value != pytest.approx(
            simulated_result.var_value, rel=0.01
        )


class TestHistoricalVaRMultiAssetActualReturns:
    def test_multi_asset_historical_var_uses_actual_returns(self):
        """Multi-asset historical VaR with real returns correctly
        computes portfolio losses across asset classes."""
        exposures = [
            AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20),
            AssetClassExposure(AssetClass.FIXED_INCOME, 200_000.0, 0.06),
        ]
        corr = np.array([[1.0, -0.2], [-0.2, 1.0]])

        rng = np.random.default_rng(123)
        returns = rng.normal(0, 0.01, size=(250, 2))

        result = calculate_historical_var(
            exposures,
            ConfidenceLevel.CL_95,
            1,
            corr,
            historical_returns=returns,
        )

        assert result.var_value > 0
        assert len(result.component_breakdown) == 2
