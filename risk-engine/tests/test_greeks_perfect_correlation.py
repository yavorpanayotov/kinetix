"""Tests for the Greeks calculation when assets are perfectly correlated.

Perfectly correlated assets (rho = 1) effectively collapse to a single
risk factor — the gradient of the portfolio value with respect to one
asset equals the gradient with respect to the other (up to vol
scaling). The Greeks computation should remain numerically stable in
that limit; the PSD-repaired correlation matrix lets the Cholesky
factor exist.
"""

import numpy as np
import pytest


@pytest.mark.unit
def test_repaired_correlation_with_rho_one_is_cholesky_factorable():
    """A 2x2 correlation matrix with rho = +1 is singular; PSD repair
    clips the zero eigenvalue and Cholesky succeeds.
    """
    from kinetix_risk.var_monte_carlo import _nearest_positive_definite

    corr = np.array([[1.0, 1.0], [1.0, 1.0]])
    repaired = _nearest_positive_definite(corr)
    factor = np.linalg.cholesky(repaired)
    # Cholesky factor exists and has the expected shape.
    assert factor.shape == (2, 2)
    # Reconstruct: L @ L.T should approximate the repaired matrix.
    reconstructed = factor @ factor.T
    np.testing.assert_allclose(reconstructed, repaired, rtol=1e-6, atol=1e-9)


@pytest.mark.unit
def test_repaired_correlation_with_rho_minus_one_is_cholesky_factorable():
    """rho = -1 is also singular but PSD-repairable to a near-PSD matrix."""
    from kinetix_risk.var_monte_carlo import _nearest_positive_definite

    corr = np.array([[1.0, -1.0], [-1.0, 1.0]])
    repaired = _nearest_positive_definite(corr)
    factor = np.linalg.cholesky(repaired)
    assert factor.shape == (2, 2)


@pytest.mark.unit
def test_repaired_correlation_3x3_perfectly_correlated_block():
    """All three assets perfectly correlated — rank 1 — PSD-repaired."""
    from kinetix_risk.var_monte_carlo import _nearest_positive_definite

    corr = np.ones((3, 3))
    repaired = _nearest_positive_definite(corr)
    factor = np.linalg.cholesky(repaired)
    assert factor.shape == (3, 3)
    # Largest eigenvalue is approximately 3 (the rank-1 eigenvalue of the
    # all-ones matrix); smallest is the 1e-10 floor.
    eigenvalues = np.linalg.eigvalsh(repaired)
    np.testing.assert_allclose(eigenvalues.max(), 3.0, rtol=1e-6)
    assert eigenvalues.min() > 0
