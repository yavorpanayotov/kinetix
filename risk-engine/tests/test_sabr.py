"""Tests for the SABR implied-vol model."""

import pytest

from kinetix_risk.sabr import SabrParameters, sabr_implied_vol


@pytest.mark.unit
def test_sabr_atm_returns_alpha_to_a_first_approximation():
    """At ATM (F=K) and zero higher-order terms, SABR reduces to
    alpha / F^(1-beta). For beta=1 (lognormal CEV), this is just
    alpha."""
    p = SabrParameters(alpha=0.20, beta=1.0, rho=0.0, nu=0.0)
    iv = sabr_implied_vol(p, forward=100.0, strike=100.0, time_to_expiry=1.0)
    assert iv == pytest.approx(0.20, abs=1e-6)


@pytest.mark.unit
def test_sabr_smile_off_atm_differs_from_atm():
    p = SabrParameters(alpha=0.20, beta=0.5, rho=-0.3, nu=0.3)
    atm = sabr_implied_vol(p, 100.0, 100.0, 1.0)
    otm = sabr_implied_vol(p, 100.0, 110.0, 1.0)
    assert otm != atm


@pytest.mark.unit
def test_sabr_constructor_rejects_invalid_beta():
    with pytest.raises(ValueError):
        SabrParameters(alpha=0.2, beta=-0.1, rho=0.0, nu=0.3)
    with pytest.raises(ValueError):
        SabrParameters(alpha=0.2, beta=1.5, rho=0.0, nu=0.3)


@pytest.mark.unit
def test_sabr_constructor_rejects_invalid_rho():
    with pytest.raises(ValueError):
        SabrParameters(alpha=0.2, beta=0.5, rho=1.01, nu=0.3)


@pytest.mark.unit
def test_sabr_constructor_rejects_negative_nu():
    with pytest.raises(ValueError):
        SabrParameters(alpha=0.2, beta=0.5, rho=0.0, nu=-0.1)


@pytest.mark.unit
def test_sabr_constructor_rejects_non_positive_alpha():
    with pytest.raises(ValueError):
        SabrParameters(alpha=0.0, beta=0.5, rho=0.0, nu=0.3)


@pytest.mark.unit
def test_sabr_rejects_non_positive_inputs_at_call_site():
    p = SabrParameters(alpha=0.20, beta=0.5, rho=0.0, nu=0.3)
    with pytest.raises(ValueError):
        sabr_implied_vol(p, 0.0, 100.0, 1.0)
    with pytest.raises(ValueError):
        sabr_implied_vol(p, 100.0, 100.0, 0.0)
