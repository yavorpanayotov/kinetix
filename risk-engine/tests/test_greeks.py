import numpy as np
import pytest

from kinetix_risk.greeks import calculate_greeks
from kinetix_risk.models import (
    AssetClass, CalculationType, ConfidenceLevel, GreeksResult, PositionRisk,
)
from kinetix_risk.portfolio_risk import calculate_book_var
from kinetix_risk.volatility import DEFAULT_VOLATILITIES, VolatilityProvider


pytestmark = pytest.mark.unit


def _sample_positions() -> list[PositionRisk]:
    return [
        PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
        PositionRisk("UST10Y", AssetClass.FIXED_INCOME, 500_000.0, "USD"),
        PositionRisk("GOLD", AssetClass.COMMODITY, 300_000.0, "USD"),
    ]


class TestGreeksCalculation:
    def test_delta_is_positive_for_long_portfolio(self):
        positions = _sample_positions()
        result = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            book_id="port-1",
        )
        # For dominant asset class (EQUITY), price increase → higher VaR → positive delta
        assert result.delta[AssetClass.EQUITY] > 0
        # FIXED_INCOME can have negative delta due to negative correlation (diversification)
        assert result.delta[AssetClass.FIXED_INCOME] != 0

    def test_gamma_captures_convexity(self):
        positions = _sample_positions()
        result = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        # Gamma (second derivative) should exist for each asset class
        for ac in result.gamma:
            assert isinstance(result.gamma[ac], float)

    def test_vega_is_positive(self):
        positions = _sample_positions()
        result = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        # Higher vol → higher VaR → positive vega for dominant asset classes
        assert result.vega[AssetClass.EQUITY] > 0
        assert result.vega[AssetClass.COMMODITY] > 0
        # FIXED_INCOME vega can be negative due to diversification effects
        assert result.vega[AssetClass.FIXED_INCOME] != 0

    def test_theta_is_nonzero(self):
        positions = _sample_positions()
        result = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        assert result.theta != 0.0

    def test_rho_is_nonzero(self):
        positions = _sample_positions()
        result = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        assert result.rho != 0.0

    def test_greeks_per_asset_class(self):
        positions = _sample_positions()
        result = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        assert isinstance(result, GreeksResult)
        expected_acs = {AssetClass.EQUITY, AssetClass.FIXED_INCOME, AssetClass.COMMODITY}
        assert set(result.delta.keys()) == expected_acs
        assert set(result.gamma.keys()) == expected_acs
        assert set(result.vega.keys()) == expected_acs

    def test_empty_positions_raises(self):
        with pytest.raises(ValueError, match="empty positions"):
            calculate_greeks(
                [], CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            )

    def test_greeks_skips_base_var_when_provided(self):
        positions = _sample_positions()
        base_var = 50_000.0
        result = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            book_id="port-1",
            base_var_value=base_var,
        )
        # When base_var_value is provided, Greeks use it instead of computing their own
        assert isinstance(result, GreeksResult)
        assert result.delta[AssetClass.EQUITY] != 0
        assert result.theta != 0.0

    def test_rho_measures_rate_sensitivity_not_vol_sensitivity(self):
        """Rho should be computed by bumping the risk-free rate, not volatilities.

        We verify that calculate_greeks passes a risk_free_rate to the VaR
        calculation (via _var_value). The function signature must accept and
        use a risk_free_rate parameter — the old code bumped vols instead.
        """
        positions = _sample_positions()

        from kinetix_risk.greeks import _var_value
        import inspect
        sig = inspect.signature(_var_value)
        assert "risk_free_rate" in sig.parameters, (
            "_var_value must accept a risk_free_rate parameter for proper rho computation"
        )

        # Compute greeks — rho should reflect rate sensitivity
        result = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )

        # With the fixed implementation, rho comes from bumping risk_free_rate,
        # which discounts market values via exp(-rate * T/252). For a 1-day
        # horizon the discount effect is small but nonzero.
        assert result.rho != 0.0

    def test_rho_increases_with_time_horizon(self):
        """Longer-dated positions have more rate sensitivity, so rho should
        increase (in absolute value) as the time horizon grows.

        This also validates that calculate_book_var accepts a risk_free_rate
        parameter, since proper rho computation requires discounting market
        values by the risk-free rate.
        """
        import inspect
        from kinetix_risk.portfolio_risk import calculate_book_var
        sig = inspect.signature(calculate_book_var)
        assert "risk_free_rate" in sig.parameters, (
            "calculate_book_var must accept a risk_free_rate parameter"
        )

        positions = _sample_positions()

        result_1d = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )
        result_10d = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 10,
        )

        assert abs(result_10d.rho) > abs(result_1d.rho), (
            "Rho should increase in absolute value with longer time horizon"
        )


class TestGreeksThreadModelInputs:
    """Regression tests for kx-ohul.

    The per-asset-class Greeks grid used to show near-identical values across
    every asset class (delta ~= -5M for all, gamma ~= -1bn for all). The cause
    was that the bumped VaRs were computed against the default static model
    while the base VaR used the threaded market-data model. The constant model
    gap dwarfed the bump, degenerating the finite differences into a near-constant
    value uniform across asset classes.
    """

    def _multi_asset_positions(self) -> list[PositionRisk]:
        return [
            PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
            PositionRisk("UST10Y", AssetClass.FIXED_INCOME, 800_000.0, "USD"),
            PositionRisk("EURUSD", AssetClass.FX, 600_000.0, "USD"),
            PositionRisk("SPX_CALL", AssetClass.DERIVATIVE, 400_000.0, "USD"),
        ]

    def _non_default_model(self):
        """A volatility provider and correlation matrix that differ from the
        engine defaults, mirroring market-data threaded from the base valuation."""
        vols = {
            AssetClass.EQUITY: 0.35,
            AssetClass.FIXED_INCOME: 0.12,
            AssetClass.FX: 0.18,
            AssetClass.DERIVATIVE: 0.45,
        }
        vol_provider = VolatilityProvider.from_dict(vols)
        # Full 5x5 correlation matrix (EQUITY, FI, FX, COMMODITY, DERIVATIVE)
        # with clearly non-default off-diagonals.
        corr = np.array([
            [1.00, 0.10, 0.50, 0.20, 0.85],
            [0.10, 1.00, 0.15, 0.10, 0.05],
            [0.50, 0.15, 1.00, 0.30, 0.45],
            [0.20, 0.10, 0.30, 1.00, 0.25],
            [0.85, 0.05, 0.45, 0.25, 1.00],
        ])
        return vol_provider, corr

    def test_per_asset_class_deltas_are_not_all_equal(self):
        positions = self._multi_asset_positions()
        vol_provider, corr = self._non_default_model()

        base = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            volatility_provider=vol_provider, correlation_matrix=corr,
        )

        result = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            book_id="multi",
            base_var_value=base.var_value,
            volatility_provider=vol_provider,
            correlation_matrix=corr,
        )

        deltas = [result.delta[ac] for ac in result.delta]
        assert len(deltas) == 4
        # The bug produced near-identical deltas across classes. With the model
        # threaded through, deltas should vary materially by asset class.
        assert float(np.std(deltas)) > 1.0, (
            f"Per-asset-class deltas should differ; got {result.delta}"
        )

        # And the magnitudes should be plausible, not the ~-1e9 gamma artefact.
        for ac, g in result.gamma.items():
            assert abs(g) < 1e8, (
                f"Gamma for {ac} is implausibly large ({g}); model gap not threaded"
            )

    def test_threaded_model_changes_greeks_vs_default(self):
        """Threading a non-default model must actually influence the Greeks,
        proving the inputs are forwarded to the bumped VaRs rather than ignored."""
        positions = self._multi_asset_positions()
        vol_provider, corr = self._non_default_model()

        base_threaded = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            volatility_provider=vol_provider, correlation_matrix=corr,
        )
        threaded = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
            base_var_value=base_threaded.var_value,
            volatility_provider=vol_provider,
            correlation_matrix=corr,
        )
        default = calculate_greeks(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )

        assert threaded.delta[AssetClass.EQUITY] != default.delta[AssetClass.EQUITY]

    def test_monte_carlo_reuses_base_seed_for_small_bump(self):
        """For MONTE_CARLO the bumped runs must reuse the base seed; otherwise
        RNG variance swamps a small bump and the delta blows up. With the seed
        reused, a 1% bump yields a delta on the order of the position value, not
        an RNG-scale artefact."""
        positions = self._multi_asset_positions()
        seed = 4242

        base = calculate_book_var(
            positions, CalculationType.MONTE_CARLO, ConfidenceLevel.CL_95, 1,
            num_simulations=2_000, seed=seed,
        )
        result = calculate_greeks(
            positions, CalculationType.MONTE_CARLO, ConfidenceLevel.CL_95, 1,
            base_var_value=base.var_value,
            num_simulations=2_000,
            seed=seed,
        )

        # Total notional is ~2.8M. With the seed reused, the finite-difference
        # delta should be comparable to that scale, not orders of magnitude larger
        # (which is what unseeded RNG variance over a 1% bump produces).
        for ac, d in result.delta.items():
            assert abs(d) < 1e8, (
                f"Monte Carlo delta for {ac} is RNG-scale ({d}); base seed not reused"
            )
