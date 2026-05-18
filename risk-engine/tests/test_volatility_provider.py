import numpy as np
import pytest

from kinetix_risk.models import (
    AssetClass,
    CalculationType,
    ConfidenceLevel,
    PositionRisk,
)
from kinetix_risk.portfolio_risk import calculate_book_var
from kinetix_risk.volatility import DEFAULT_VOLATILITIES, VolatilityProvider


pytestmark = pytest.mark.unit


class TestVolatilityProvider:
    def test_static_provider_returns_default_vols(self):
        provider = VolatilityProvider.static()
        for ac, expected_vol in DEFAULT_VOLATILITIES.items():
            assert provider(ac) == expected_vol

    def test_custom_provider_overrides_vols(self):
        custom = {AssetClass.EQUITY: 0.50}
        provider = VolatilityProvider.from_dict(custom)
        assert provider(AssetClass.EQUITY) == 0.50
        # Fallback to default for non-overridden
        assert provider(AssetClass.FX) == DEFAULT_VOLATILITIES[AssetClass.FX]

    def test_portfolio_var_uses_custom_provider(self):
        positions = [
            PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
        ]
        default_result = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        high_vol_provider = VolatilityProvider.from_dict({AssetClass.EQUITY: 0.50})
        high_vol_result = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            volatility_provider=high_vol_provider,
        )
        assert high_vol_result.var_value > default_result.var_value

    def test_portfolio_var_default_provider_unchanged(self):
        positions = [
            PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
        ]
        result_no_provider = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        result_static = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            volatility_provider=VolatilityProvider.static(),
        )
        assert abs(result_no_provider.var_value - result_static.var_value) < 1e-10

    def test_jittered_provider_returns_values_near_defaults(self):
        provider = VolatilityProvider.with_jitter()
        for ac, default_vol in DEFAULT_VOLATILITIES.items():
            vol = provider(ac)
            assert vol == pytest.approx(default_vol, rel=0.15), (
                f"{ac}: jittered vol {vol} too far from default {default_vol}"
            )

    def test_jittered_provider_produces_varying_results(self):
        provider_a = VolatilityProvider.with_jitter()
        provider_b = VolatilityProvider.with_jitter()
        any_differ = any(
            provider_a(ac) != provider_b(ac) for ac in AssetClass
        )
        assert any_differ, "Two jittered providers should produce different values"

    def test_ewma_factory_returns_provider_with_estimated_vols(self):
        rng = np.random.default_rng(10)
        equity_returns = rng.normal(0, 0.012, size=250)

        provider = VolatilityProvider.ewma(
            returns_by_asset_class={AssetClass.EQUITY: equity_returns},
        )

        eq_vol = provider(AssetClass.EQUITY)
        assert eq_vol > 0
        # Should differ from static default (0.20)
        assert eq_vol != pytest.approx(0.20, abs=0.01)
        # Falls back for missing asset classes
        assert provider(AssetClass.COMMODITY) == pytest.approx(
            DEFAULT_VOLATILITIES[AssetClass.COMMODITY], rel=1e-6,
        )
