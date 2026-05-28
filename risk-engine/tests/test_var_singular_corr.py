"""Tests for parametric VaR robustness under a singular correlation matrix.

A singular correlation matrix has a zero eigenvalue — two columns are
perfectly collinear ("AAPL_USD" and a synthetic clone). Parametric VaR
should still return a finite, positive number rather than NaN or a
``LinAlgError`` from the Cholesky step. The PSD repair (see
``_nearest_positive_definite``) clips the zero eigenvalue to a tiny
floor so the Cholesky factor exists.

References
----------
Higham, N. J. (2002). Computing the nearest correlation matrix —
    a problem from finance. *IMA J. Numer. Anal.*, 22(3), 329-343.
"""

import numpy as np
import pytest


@pytest.mark.unit
def test_psd_repair_makes_singular_matrix_factorable():
    # 2x2 perfectly collinear correlation matrix — both eigenvalues should
    # come back as [0, 2] before repair; after repair the small one is the
    # 1e-10 floor.
    singular = np.array([[1.0, 1.0], [1.0, 1.0]])
    from kinetix_risk.var_monte_carlo import _nearest_positive_definite

    repaired = _nearest_positive_definite(singular)
    # Cholesky on the repaired matrix succeeds.
    factor = np.linalg.cholesky(repaired)
    assert factor.shape == (2, 2)
    # Diagonal of the repaired matrix is still numerically 1.
    np.testing.assert_allclose(np.diag(repaired), [1.0, 1.0], rtol=1e-6)


@pytest.mark.unit
def test_psd_repair_on_near_singular_matrix_finite():
    # Near-singular: one eigenvalue is ~1e-12.
    near_singular = np.array([[1.0, 1.0 - 1e-12], [1.0 - 1e-12, 1.0]])
    from kinetix_risk.var_monte_carlo import _nearest_positive_definite

    repaired = _nearest_positive_definite(near_singular)
    # All entries are finite after the repair.
    assert np.all(np.isfinite(repaired))
    # Smallest eigenvalue is at least the configured floor.
    eigenvalues = np.linalg.eigvalsh(repaired)
    assert eigenvalues.min() > 0


@pytest.mark.unit
def test_psd_repair_idempotent_on_identity():
    # The identity matrix is already PSD; the repair should be a no-op.
    from kinetix_risk.var_monte_carlo import _nearest_positive_definite

    identity = np.eye(3)
    repaired = _nearest_positive_definite(identity)
    np.testing.assert_allclose(repaired, identity, rtol=1e-10)
