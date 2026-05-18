import numpy as np
import pytest

from kinetix_risk.models import AssetClass, AssetClassExposure, ConfidenceLevel
from kinetix_risk.monte_carlo import calculate_monte_carlo_var_antithetic


pytestmark = pytest.mark.unit


def _sample_exposures() -> list[AssetClassExposure]:
    return [
        AssetClassExposure(AssetClass.EQUITY, 1_000_000.0, 0.20),
        AssetClassExposure(AssetClass.FIXED_INCOME, 500_000.0, 0.06),
        AssetClassExposure(AssetClass.COMMODITY, 300_000.0, 0.25),
    ]


def _correlation_matrix() -> np.ndarray:
    return np.array([
        [1.00, -0.20, 0.40],
        [-0.20, 1.00, -0.05],
        [0.40, -0.05, 1.00],
    ])


class TestAntitheticVarianceReduction:
    def test_main_mc_path_uses_antithetic_variates(self):
        """The main MC VaR path (var_monte_carlo) should produce identical
        results to the dedicated antithetic module, confirming both use
        antithetic variates."""
        exposures = _sample_exposures()
        corr = _correlation_matrix()

        from kinetix_risk.var_monte_carlo import calculate_monte_carlo_var
        main_result = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 1, corr,
            num_simulations=5000, seed=42,
        )
        anti_result = calculate_monte_carlo_var_antithetic(
            exposures, ConfidenceLevel.CL_95, 1, corr,
            num_simulations=5000, seed=42,
        )

        assert main_result.var_value == pytest.approx(anti_result.var_value, rel=1e-6), (
            f"Main MC path ({main_result.var_value:.2f}) should match antithetic "
            f"({anti_result.var_value:.2f}) — both should use antithetic variates"
        )

    def test_antithetic_var_is_close_to_standard(self):
        """The mean VaR from antithetic variates should be close to standard MC."""
        exposures = _sample_exposures()
        corr = _correlation_matrix()
        n_sims = 50_000

        from kinetix_risk.var_monte_carlo import calculate_monte_carlo_var
        std_result = calculate_monte_carlo_var(
            exposures, ConfidenceLevel.CL_95, 1, corr,
            num_simulations=n_sims, seed=42,
        )
        anti_result = calculate_monte_carlo_var_antithetic(
            exposures, ConfidenceLevel.CL_95, 1, corr,
            num_simulations=n_sims, seed=42,
        )

        # VaR values should be within 10% of each other
        relative_diff = abs(std_result.var_value - anti_result.var_value) / std_result.var_value
        assert relative_diff < 0.10, (
            f"VaR values differ by {relative_diff:.1%}: "
            f"standard={std_result.var_value:.2f}, antithetic={anti_result.var_value:.2f}"
        )

    def test_antithetic_doubles_effective_paths(self):
        """Antithetic variates with N simulations should use 2N paths
        (N original + N antithetic), producing results comparable to 2N
        standard simulations."""
        exposures = _sample_exposures()
        corr = _correlation_matrix()
        n_trials = 30

        # Standard MC with 2N paths
        standard_2n = []
        for i in range(n_trials):
            from kinetix_risk.var_monte_carlo import calculate_monte_carlo_var
            result = calculate_monte_carlo_var(
                exposures, ConfidenceLevel.CL_95, 1, corr,
                num_simulations=4000, seed=i,
            )
            standard_2n.append(result.var_value)

        # Antithetic with N paths (which generates 2N effective paths)
        antithetic_n = []
        for i in range(n_trials):
            result = calculate_monte_carlo_var_antithetic(
                exposures, ConfidenceLevel.CL_95, 1, corr,
                num_simulations=2000, seed=i + 1000,
            )
            antithetic_n.append(result.var_value)

        # Variance of antithetic-N should be in the same ballpark as standard-2N
        var_std_2n = np.var(standard_2n)
        var_anti_n = np.var(antithetic_n)
        # Antithetic should not be wildly worse than double-sampled standard
        assert var_anti_n < var_std_2n * 3, (
            f"Antithetic N variance ({var_anti_n:.2f}) is much larger than "
            f"standard 2N variance ({var_std_2n:.2f})"
        )

    def test_antithetic_returns_valid_result_structure(self):
        """Antithetic MC should return a valid VaRResult with component breakdown."""
        exposures = _sample_exposures()
        corr = _correlation_matrix()
        result = calculate_monte_carlo_var_antithetic(
            exposures, ConfidenceLevel.CL_95, 1, corr,
            num_simulations=5000, seed=42,
        )
        assert result.var_value > 0
        assert result.expected_shortfall >= result.var_value
        assert len(result.component_breakdown) == len(exposures)
