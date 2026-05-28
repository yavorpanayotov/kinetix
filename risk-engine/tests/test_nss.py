"""Tests for the Nelson-Siegel-Svensson yield-curve model."""

import pytest

from kinetix_risk.nss import NssParameters, nss_yield


@pytest.mark.unit
def test_nss_long_end_approaches_beta0():
    """At very long maturity, all the slope and hump factors vanish
    and yield -> beta0 (the level parameter)."""
    p = NssParameters(beta0=0.05, beta1=-0.02, beta2=0.01, beta3=0.005, tau1=2.0, tau2=5.0)
    assert nss_yield(p, maturity_years=200.0) == pytest.approx(0.05, abs=1e-3)


@pytest.mark.unit
def test_nss_short_end_is_beta0_plus_beta1():
    """At t -> 0+, factor1 -> 1, factor2 -> 0, factor3 -> 0, so
    yield -> beta0 + beta1 (level + slope)."""
    p = NssParameters(beta0=0.05, beta1=-0.02, beta2=0.01, beta3=0.005, tau1=2.0, tau2=5.0)
    assert nss_yield(p, maturity_years=0.0) == pytest.approx(0.03)


@pytest.mark.unit
def test_nss_intermediate_yield_between_short_and_long():
    p = NssParameters(beta0=0.05, beta1=-0.02, beta2=0.01, beta3=0.005, tau1=2.0, tau2=5.0)
    y_short = nss_yield(p, 0.0)
    y_long = nss_yield(p, 200.0)
    y_mid = nss_yield(p, 5.0)
    assert min(y_short, y_long) - 0.05 <= y_mid <= max(y_short, y_long) + 0.05


@pytest.mark.unit
def test_nss_constructor_rejects_non_positive_tau():
    with pytest.raises(ValueError):
        NssParameters(beta0=0.05, beta1=-0.02, beta2=0.01, beta3=0.005, tau1=0.0, tau2=5.0)
    with pytest.raises(ValueError):
        NssParameters(beta0=0.05, beta1=-0.02, beta2=0.01, beta3=0.005, tau1=2.0, tau2=-1.0)
