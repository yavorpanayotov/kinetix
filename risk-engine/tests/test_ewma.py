import numpy as np
import pytest

from kinetix_risk.ewma import ewma_volatility, EwmaVolatilityProvider
from kinetix_risk.models import AssetClass
from kinetix_risk.volatility import VolatilityProvider


pytestmark = pytest.mark.unit


class TestEwmaVolatility:
    def test_ewma_vol_with_constant_returns_converges_to_realized(self):
        """When returns are constant in magnitude the EWMA vol should
        converge to that magnitude (annualized)."""
        daily_return = 0.01
        returns = np.full(500, daily_return)

        vol = ewma_volatility(returns)

        # For constant |r|, EWMA variance converges to r^2, so vol -> |r|
        assert vol == pytest.approx(daily_return, rel=0.05)

    def test_ewma_vol_reacts_to_volatility_spike(self):
        """After a volatility spike the EWMA vol should increase
        relative to a calm period."""
        calm = np.full(100, 0.005)
        spike = np.full(10, 0.05)

        vol_calm = ewma_volatility(calm)
        vol_after_spike = ewma_volatility(np.concatenate([calm, spike]))

        assert vol_after_spike > vol_calm

    def test_ewma_default_lambda_is_0_94(self):
        """The default decay factor should be 0.94 (RiskMetrics standard)."""
        returns = np.array([0.01, -0.02, 0.015])
        vol_default = ewma_volatility(returns)
        vol_explicit = ewma_volatility(returns, decay_factor=0.94)
        assert vol_default == pytest.approx(vol_explicit, rel=1e-10)

    def test_ewma_with_single_return_uses_initial_vol(self):
        """With a single return and an explicit initial_vol, the result
        should blend the initial vol with the single observation."""
        returns = np.array([0.02])
        vol = ewma_volatility(returns, initial_vol=0.01)

        # EWMA variance: lambda * initial_vol^2 + (1-lambda) * r^2
        expected_var = 0.94 * 0.01**2 + 0.06 * 0.02**2
        expected_vol = np.sqrt(expected_var)
        assert vol == pytest.approx(expected_vol, rel=1e-6)

    def test_ewma_with_empty_returns_and_initial_vol(self):
        """With no returns, should return the initial vol."""
        vol = ewma_volatility(np.array([]), initial_vol=0.015)
        assert vol == pytest.approx(0.015, rel=1e-10)

    def test_ewma_with_empty_returns_and_no_initial_vol_raises(self):
        """With no returns and no initial vol, should raise ValueError."""
        with pytest.raises(ValueError):
            ewma_volatility(np.array([]))


class TestEwmaVolatilityProvider:
    def test_ewma_provider_returns_ewma_estimated_vols(self):
        """EwmaVolatilityProvider should produce a VolatilityProvider whose
        volatilities are driven by the EWMA estimate of each asset class."""
        rng = np.random.default_rng(42)
        equity_returns = rng.normal(0, 0.015, size=250)
        fi_returns = rng.normal(0, 0.004, size=250)

        provider = EwmaVolatilityProvider(
            returns_by_asset_class={
                AssetClass.EQUITY: equity_returns,
                AssetClass.FIXED_INCOME: fi_returns,
            },
        ).get_provider()

        assert isinstance(provider, VolatilityProvider)
        eq_vol = provider(AssetClass.EQUITY)
        fi_vol = provider(AssetClass.FIXED_INCOME)

        # Equity vol should be higher than fixed income vol
        assert eq_vol > fi_vol
        # Both should be positive
        assert eq_vol > 0
        assert fi_vol > 0

    def test_ewma_provider_falls_back_to_default_for_missing_asset_class(self):
        """If no returns are provided for an asset class, the provider
        should fall back to the static default volatility."""
        from kinetix_risk.volatility import DEFAULT_VOLATILITIES

        provider = EwmaVolatilityProvider(
            returns_by_asset_class={},
        ).get_provider()

        fx_vol = provider(AssetClass.FX)
        assert fx_vol == pytest.approx(DEFAULT_VOLATILITIES[AssetClass.FX], rel=1e-6)
