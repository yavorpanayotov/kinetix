"""Tests for the EVT (GPD) tail-VaR estimator."""

import pytest

from kinetix_risk.var_evt import GpdFit, evt_var, fit_gpd_to_excesses


@pytest.mark.unit
def test_evt_var_fits_gpd_to_sufficient_excesses():
    losses = [float(i) for i in range(100)]  # 0..99
    fit = fit_gpd_to_excesses(losses, threshold=90.0)
    # 9 excesses above 90: 1, 2, 3, 4, 5, 6, 7, 8, 9.
    assert fit.n_exceedances == 9
    assert fit.threshold == 90.0


@pytest.mark.unit
def test_evt_var_no_excesses_returns_threshold_var():
    fit = fit_gpd_to_excesses(losses=[1.0, 2.0, 3.0], threshold=100.0)
    var = evt_var(fit, confidence=0.99)
    assert var == 100.0


@pytest.mark.unit
def test_evt_var_above_threshold_for_high_confidence():
    """A 99% VaR estimate from EVT should be larger than the threshold
    when there's a real fat-tail signal."""
    losses = [float(i) for i in range(1000)]
    fit = fit_gpd_to_excesses(losses, threshold=900.0)
    var_99 = evt_var(fit, confidence=0.99)
    assert var_99 >= 900.0


@pytest.mark.unit
def test_evt_var_rejects_invalid_confidence():
    fit = fit_gpd_to_excesses([float(i) for i in range(100)], threshold=90.0)
    with pytest.raises(ValueError):
        evt_var(fit, confidence=0.0)
    with pytest.raises(ValueError):
        evt_var(fit, confidence=1.0)


@pytest.mark.unit
def test_evt_var_xi_near_zero_uses_exponential_limit():
    """When the GPD shape is near zero, the formula falls back to
    the Gumbel-limit exponential form."""
    # Construct a fit with xi very small.
    fit = GpdFit(threshold=100.0, xi=1e-12, beta=10.0, n_total=1000, n_exceedances=50)
    var = evt_var(fit, confidence=0.99)
    assert var > 100.0
