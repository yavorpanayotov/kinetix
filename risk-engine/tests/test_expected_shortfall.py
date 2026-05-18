import numpy as np
import pytest

from kinetix_risk.models import ConfidenceLevel
from kinetix_risk.expected_shortfall import calculate_expected_shortfall


pytestmark = pytest.mark.unit


class TestExpectedShortfall:
    def test_sorted_losses_95(self):
        # 100 losses from 1 to 100
        # At 95%: VaR ≈ 95, ES = mean of losses >= VaR threshold ≈ 98
        losses = np.arange(1.0, 101.0)
        es = calculate_expected_shortfall(losses, ConfidenceLevel.CL_95)
        assert es == pytest.approx(98.0, abs=0.5)

    def test_sorted_losses_99(self):
        losses = np.arange(1.0, 101.0)
        es = calculate_expected_shortfall(losses, ConfidenceLevel.CL_99)
        assert es == pytest.approx(100.0, abs=0.5)

    def test_es_always_gte_var(self):
        rng = np.random.default_rng(42)
        losses = rng.normal(0, 1, 10_000)
        es = calculate_expected_shortfall(losses, ConfidenceLevel.CL_95)
        var = np.percentile(losses, 95)
        assert es >= var

    def test_uniform_distribution_es(self):
        # Uniform [0, 1]: VaR(95%) = 0.95, ES ≈ 0.975
        losses = np.linspace(0, 1, 10_001)
        es = calculate_expected_shortfall(losses, ConfidenceLevel.CL_95)
        assert es == pytest.approx(0.975, abs=0.01)

    def test_empty_losses_raises(self):
        with pytest.raises(ValueError):
            calculate_expected_shortfall(np.array([]), ConfidenceLevel.CL_95)
