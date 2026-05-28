"""Tests for risk metrics on a single-asset portfolio.

A single-asset portfolio is the degenerate case where there's no
diversification benefit — VaR equals the standalone asset's VaR, and
PSD repair of the 1x1 correlation matrix is trivial (it's just
``[[1.0]]``).
"""

import numpy as np
import pytest


@pytest.mark.unit
def test_psd_repair_of_1x1_identity_is_identity():
    """The trivial 1x1 case: input is `[[1.0]]`, output is `[[1.0]]`."""
    from kinetix_risk.var_monte_carlo import _nearest_positive_definite

    repaired = _nearest_positive_definite(np.array([[1.0]]))
    np.testing.assert_allclose(repaired, np.array([[1.0]]), rtol=1e-12)


@pytest.mark.unit
def test_cholesky_of_1x1_repaired_is_1x1():
    from kinetix_risk.var_monte_carlo import _nearest_positive_definite

    repaired = _nearest_positive_definite(np.array([[1.0]]))
    factor = np.linalg.cholesky(repaired)
    assert factor.shape == (1, 1)
    np.testing.assert_allclose(factor, np.array([[1.0]]), rtol=1e-12)


@pytest.mark.unit
def test_psd_repair_of_zero_1x1_clips_to_floor():
    """A 1x1 zero matrix has eigenvalue 0; repair clips to the 1e-10 floor."""
    from kinetix_risk.var_monte_carlo import _nearest_positive_definite

    repaired = _nearest_positive_definite(np.array([[0.0]]))
    # The clipped value is some small positive number.
    assert repaired[0, 0] > 0
    assert repaired[0, 0] < 1e-9
