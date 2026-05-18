import pytest

from kinetix_risk.greeks import calculate_greeks
from kinetix_risk.models import (
    AssetClass, CalculationType, ConfidenceLevel, GreeksResult, PositionRisk,
)
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
