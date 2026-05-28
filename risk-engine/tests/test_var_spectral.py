"""Tests for the spectral risk measure module."""

import pytest

from kinetix_risk.var_spectral import (
    expected_shortfall_weights,
    exponential_weights,
    spectral_risk_measure,
)


@pytest.mark.unit
def test_spectral_risk_empty_losses_is_zero():
    weights = expected_shortfall_weights(0.95)
    assert spectral_risk_measure([], weights) == 0.0


@pytest.mark.unit
def test_spectral_risk_es_approaches_tail_mean_for_uniform_losses():
    # 100 evenly-spaced losses from 0.0 to 99.0; ES at 95% should be
    # close to the mean of the worst 5 losses (95.5 ish).
    losses = sorted(range(100))
    weights = expected_shortfall_weights(0.95)
    es = spectral_risk_measure([float(x) for x in losses], weights)
    # Worst 5 losses are 95, 96, 97, 98, 99 -> mean 97.
    assert 95.0 <= es <= 99.0


@pytest.mark.unit
def test_exponential_weights_assigns_more_weight_to_higher_quantiles():
    """With gamma > 0, phi(0.99) > phi(0.5) > phi(0.01)."""
    phi = exponential_weights(gamma=3.0)
    assert phi(0.99) > phi(0.5) > phi(0.01)


@pytest.mark.unit
def test_es_weights_zero_below_alpha_constant_above():
    phi = expected_shortfall_weights(0.95)
    assert phi(0.50) == 0
    assert phi(0.97) == pytest.approx(1 / 0.05)
    assert phi(0.99) == pytest.approx(1 / 0.05)


@pytest.mark.unit
def test_es_weights_rejects_invalid_alpha():
    with pytest.raises(ValueError):
        expected_shortfall_weights(1.0)
    with pytest.raises(ValueError):
        expected_shortfall_weights(-0.1)


@pytest.mark.unit
def test_exponential_weights_rejects_non_positive_gamma():
    with pytest.raises(ValueError):
        exponential_weights(0.0)
    with pytest.raises(ValueError):
        exponential_weights(-1.0)
