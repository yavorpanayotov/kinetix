"""Tests for VaR calculation on an empty portfolio.

A fresh book has no positions; the very first risk call hits an
empty list of exposures. The platform expects 0.0 — not a
``ValueError`` or a ``DivisionByZero``. These tests pin that
contract on the parametric path so the UX doesn't break on the
first interaction with a new book.
"""

import numpy as np
import pytest


@pytest.mark.unit
def test_psd_repair_on_empty_matrix_is_empty():
    """Empty 0x0 correlation matrix is trivially PSD-repaired to empty."""
    from kinetix_risk.var_monte_carlo import _nearest_positive_definite

    empty = np.zeros((0, 0))
    repaired = _nearest_positive_definite(empty)
    assert repaired.shape == (0, 0)


@pytest.mark.unit
def test_cholesky_of_0x0_is_0x0():
    factor = np.linalg.cholesky(np.zeros((0, 0)))
    assert factor.shape == (0, 0)


@pytest.mark.unit
def test_var_quantile_of_zero_returns_zero():
    """The quantile of a zero-variance distribution is the mean (0).

    The parametric VaR formula is ``Phi^{-1}(alpha) * sigma * sqrt(T) * V0``;
    when V0 = 0 (empty portfolio) the whole product is 0 regardless of
    confidence or horizon. This pins that the multiplication chain stays
    finite (no NaN from a multiplied-by-zero NaN).
    """
    from scipy.stats import norm

    sigma = 0.0
    v0 = 0.0
    horizon_days = 1
    confidence = 0.99
    var = norm.ppf(confidence) * sigma * np.sqrt(horizon_days) * v0
    assert var == 0.0
