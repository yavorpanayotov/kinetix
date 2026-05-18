import numpy as np
import pytest

from kinetix_risk.correlation import estimate_correlation


pytestmark = pytest.mark.unit


class TestLedoitWolfCorrelation:
    def test_ledoit_wolf_returns_symmetric_positive_definite_matrix(self):
        """The estimated correlation matrix must be symmetric and positive definite."""
        rng = np.random.default_rng(42)
        n_assets = 4
        n_obs = 200
        returns = rng.standard_normal((n_obs, n_assets))

        corr = estimate_correlation(returns, method="ledoit_wolf")

        # Symmetric
        np.testing.assert_array_almost_equal(corr, corr.T, decimal=10)

        # Positive definite: all eigenvalues > 0
        eigenvalues = np.linalg.eigvalsh(corr)
        assert np.all(eigenvalues > 0), (
            f"Matrix is not positive definite, min eigenvalue: {eigenvalues.min()}"
        )

        # Diagonal should be 1.0 (it's a correlation matrix, not covariance)
        np.testing.assert_array_almost_equal(np.diag(corr), np.ones(n_assets), decimal=10)

    def test_ledoit_wolf_with_known_correlation(self):
        """Given perfectly correlated data, estimated correlation should be near 1.0."""
        rng = np.random.default_rng(123)
        n_obs = 500
        base = rng.standard_normal(n_obs)
        # Two assets that are perfectly correlated (plus tiny noise for numerical stability)
        noise = rng.standard_normal(n_obs) * 0.01
        returns = np.column_stack([base, base + noise])

        corr = estimate_correlation(returns, method="ledoit_wolf")

        # Off-diagonal should be close to 1.0 (shrinkage will pull slightly toward 0)
        assert corr[0, 1] > 0.9, f"Expected high correlation, got {corr[0, 1]}"
        assert corr[1, 0] > 0.9, f"Expected high correlation, got {corr[1, 0]}"

    def test_shrinkage_moves_toward_identity(self):
        """Ledoit-Wolf shrinkage should pull the sample correlation toward the identity.

        With small sample size relative to dimension, the shrinkage should be
        noticeable -- correlations should be smaller than the sample estimate.
        """
        rng = np.random.default_rng(99)
        n_assets = 10
        n_obs = 30  # small sample → more shrinkage

        # Create correlated returns
        true_cov = np.eye(n_assets) + 0.5 * np.ones((n_assets, n_assets))
        L = np.linalg.cholesky(true_cov)
        returns = rng.standard_normal((n_obs, n_assets)) @ L.T

        corr_lw = estimate_correlation(returns, method="ledoit_wolf")

        # Compute sample correlation for comparison
        sample_cov = np.cov(returns, rowvar=False)
        d = np.sqrt(np.diag(sample_cov))
        sample_corr = sample_cov / np.outer(d, d)

        # Average off-diagonal of shrunk should be closer to zero than sample
        mask = ~np.eye(n_assets, dtype=bool)
        avg_lw = np.abs(corr_lw[mask]).mean()
        avg_sample = np.abs(sample_corr[mask]).mean()

        assert avg_lw <= avg_sample, (
            f"Shrinkage did not pull toward identity: "
            f"LW avg={avg_lw:.4f}, sample avg={avg_sample:.4f}"
        )

    def test_handles_short_history(self):
        """Should produce a valid correlation matrix even with very few observations."""
        rng = np.random.default_rng(7)
        n_assets = 3
        n_obs = 5  # very short history
        returns = rng.standard_normal((n_obs, n_assets))

        corr = estimate_correlation(returns, method="ledoit_wolf")

        # Must be valid: symmetric, positive semi-definite, unit diagonal
        np.testing.assert_array_almost_equal(corr, corr.T, decimal=10)
        np.testing.assert_array_almost_equal(np.diag(corr), np.ones(n_assets), decimal=10)
        eigenvalues = np.linalg.eigvalsh(corr)
        assert np.all(eigenvalues >= -1e-10), (
            f"Not positive semi-definite, min eigenvalue: {eigenvalues.min()}"
        )

    def test_default_method_is_ledoit_wolf(self):
        """Calling without method argument should default to ledoit_wolf."""
        rng = np.random.default_rng(42)
        returns = rng.standard_normal((100, 3))

        corr_default = estimate_correlation(returns)
        corr_explicit = estimate_correlation(returns, method="ledoit_wolf")

        np.testing.assert_array_almost_equal(corr_default, corr_explicit)

    def test_unknown_method_raises(self):
        """Should raise ValueError for unknown estimation method."""
        rng = np.random.default_rng(42)
        returns = rng.standard_normal((100, 3))

        with pytest.raises(ValueError, match="Unknown"):
            estimate_correlation(returns, method="unknown_method")
