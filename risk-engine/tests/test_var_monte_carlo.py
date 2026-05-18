import numpy as np
import pytest

from kinetix_risk.models import AssetClass, AssetClassExposure, ConfidenceLevel
from kinetix_risk.var_monte_carlo import calculate_monte_carlo_var


pytestmark = pytest.mark.unit


class TestMonteCarloVaRSingleAsset:
    def test_deterministic_with_seed(self):
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])
        r1 = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 1, corr,
            num_simulations=10_000, seed=42,
        )
        r2 = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 1, corr,
            num_simulations=10_000, seed=42,
        )
        assert r1.var_value == r2.var_value

    def test_var_is_positive(self):
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])
        result = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 1, corr, seed=42,
        )
        assert result.var_value > 0

    def test_time_horizon_scaling(self):
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])
        var_1d = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 1, corr, seed=42,
        )
        var_10d = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 10, corr, seed=42,
        )
        assert var_10d.var_value == pytest.approx(
            var_1d.var_value * np.sqrt(10), rel=1e-6
        )

    def test_es_greater_than_var(self):
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])
        result = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 1, corr, seed=42,
        )
        assert result.expected_shortfall > result.var_value


class TestMonteCarloConvergence:
    def test_converges_to_parametric_with_many_simulations(self):
        from kinetix_risk.var_parametric import calculate_parametric_var

        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])

        parametric = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)
        mc = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 1, corr,
            num_simulations=100_000, seed=42,
        )
        assert mc.var_value == pytest.approx(parametric.var_value, rel=0.05)

    def test_more_simulations_reduces_variance(self):
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])

        results_1k = [
            calculate_monte_carlo_var(
                exposures, ConfidenceLevel.CL_95, 1, corr,
                num_simulations=1_000, seed=s,
            ).var_value
            for s in range(10)
        ]
        results_10k = [
            calculate_monte_carlo_var(
                exposures, ConfidenceLevel.CL_95, 1, corr,
                num_simulations=10_000, seed=s,
            ).var_value
            for s in range(10)
        ]
        assert np.std(results_10k) < np.std(results_1k)


class TestMonteCarloVaRMultiAsset:
    def test_component_breakdown_present(self):
        exposures = [
            AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20),
            AssetClassExposure(AssetClass.FX, 50_000.0, 0.10),
        ]
        corr = np.array([[1.0, 0.3], [0.3, 1.0]])
        result = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 1, corr,
            num_simulations=10_000, seed=42,
        )
        assert len(result.component_breakdown) == 2

    def test_diversification_benefit(self):
        exposures = [
            AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20),
            AssetClassExposure(AssetClass.FIXED_INCOME, 100_000.0, 0.06),
        ]
        corr_neg = np.array([[1.0, -0.20], [-0.20, 1.0]])

        var_combined = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 1, corr_neg, seed=42,
        )
        var_eq_only = calculate_monte_carlo_var(
            [exposures[0]], ConfidenceLevel.CL_95, 1, np.array([[1.0]]), seed=42,
        )
        var_fi_only = calculate_monte_carlo_var(
            [exposures[1]], ConfidenceLevel.CL_95, 1, np.array([[1.0]]), seed=42,
        )
        assert var_combined.var_value < var_eq_only.var_value + var_fi_only.var_value


class TestMonteCarloNonPositiveDefiniteMatrix:
    def test_nearly_non_pd_matrix_is_repaired_automatically(self):
        """A matrix that fails Cholesky but can be repaired via nearPD should produce valid results."""
        exposures = [
            AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20),
            AssetClassExposure(AssetClass.FX, 50_000.0, 0.10),
        ]
        bad_corr = np.array([[1.0, 1.5], [1.5, 1.0]])  # not PD
        result = calculate_monte_carlo_var(exposures, ConfidenceLevel.CL_95, 1, bad_corr, seed=42)
        assert result.var_value > 0

    def test_unrepairable_matrix_raises_descriptive_error(self):
        """When nearPD repair also fails, a descriptive ValueError is raised."""
        from unittest.mock import patch
        exposures = [
            AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20),
            AssetClassExposure(AssetClass.FX, 50_000.0, 0.10),
        ]
        bad_corr = np.array([[1.0, 1.5], [1.5, 1.0]])
        with patch(
            "kinetix_risk.var_monte_carlo._nearest_positive_definite",
            return_value=bad_corr,
        ):
            with pytest.raises(ValueError, match="correlation matrix is not positive"):
                calculate_monte_carlo_var(exposures, ConfidenceLevel.CL_95, 1, bad_corr, seed=42)
