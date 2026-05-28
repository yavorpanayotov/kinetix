import numpy as np
import pytest
from scipy.stats import norm

from kinetix_risk.models import AssetClass, AssetClassExposure, ConfidenceLevel
from kinetix_risk.var_parametric import calculate_parametric_var


pytestmark = pytest.mark.unit


class TestParametricVaRSingleAsset:
    """Single asset: VaR = z * sigma_daily * market_value * sqrt(T)
    where sigma_daily = sigma_annual / sqrt(252)
    """

    def test_single_equity_95_1day(self):
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])
        result = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)

        daily_vol = 0.20 / np.sqrt(252)
        expected_var = norm.ppf(0.95) * daily_vol * 100_000
        assert result.var_value == pytest.approx(expected_var, rel=1e-6)

    def test_single_equity_99_1day(self):
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])
        result = calculate_parametric_var(exposures, ConfidenceLevel.CL_99, 1, corr)

        daily_vol = 0.20 / np.sqrt(252)
        expected_var = norm.ppf(0.99) * daily_vol * 100_000
        assert result.var_value == pytest.approx(expected_var, rel=1e-6)

    def test_time_horizon_scaling_sqrt_t(self):
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])
        var_1d = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)
        var_10d = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 10, corr)

        assert var_10d.var_value == pytest.approx(
            var_1d.var_value * np.sqrt(10), rel=1e-6
        )


class TestParametricVaRMultiAsset:
    def test_two_uncorrelated_assets(self):
        exposures = [
            AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20),
            AssetClassExposure(AssetClass.FX, 50_000.0, 0.10),
        ]
        corr = np.array([[1.0, 0.0], [0.0, 1.0]])
        result = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)

        daily_vol_eq = 0.20 / np.sqrt(252)
        daily_vol_fx = 0.10 / np.sqrt(252)
        port_std = np.sqrt(
            (daily_vol_eq * 100_000) ** 2 + (daily_vol_fx * 50_000) ** 2
        )
        expected_var = norm.ppf(0.95) * port_std
        assert result.var_value == pytest.approx(expected_var, rel=1e-6)

    def test_two_perfectly_correlated_assets(self):
        exposures = [
            AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20),
            AssetClassExposure(AssetClass.FX, 50_000.0, 0.10),
        ]
        corr = np.array([[1.0, 1.0], [1.0, 1.0]])
        result = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)

        daily_vol_eq = 0.20 / np.sqrt(252)
        daily_vol_fx = 0.10 / np.sqrt(252)
        expected_var = norm.ppf(0.95) * (
            daily_vol_eq * 100_000 + daily_vol_fx * 50_000
        )
        assert result.var_value == pytest.approx(expected_var, rel=1e-6)

    def test_diversification_benefit(self):
        exposures = [
            AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20),
            AssetClassExposure(AssetClass.FIXED_INCOME, 100_000.0, 0.06),
        ]
        corr_neg = np.array([[1.0, -0.20], [-0.20, 1.0]])
        corr_zero = np.array([[1.0, 0.0], [0.0, 1.0]])

        var_neg = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr_neg)
        var_zero = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr_zero)

        assert var_neg.var_value < var_zero.var_value


class TestParametricVaRComponentBreakdown:
    def test_single_asset_component_is_100_percent(self):
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])
        result = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)

        assert len(result.component_breakdown) == 1
        assert result.component_breakdown[0].asset_class == AssetClass.EQUITY
        assert result.component_breakdown[0].percentage_of_total == pytest.approx(100.0)

    def test_two_asset_components_sum_to_total(self):
        exposures = [
            AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20),
            AssetClassExposure(AssetClass.FX, 50_000.0, 0.10),
        ]
        corr = np.array([[1.0, 0.3], [0.3, 1.0]])
        result = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)

        total_contributions = sum(c.var_contribution for c in result.component_breakdown)
        assert total_contributions == pytest.approx(result.var_value, rel=1e-4)


class TestParametricVaRExpectedShortfall:
    def test_es_greater_than_var(self):
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])
        result = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)

        assert result.expected_shortfall > result.var_value

    def test_es_analytically_correct_for_normal(self):
        # For normal distribution: ES = sigma * phi(z_alpha) / (1 - alpha)
        exposures = [AssetClassExposure(AssetClass.EQUITY, 100_000.0, 0.20)]
        corr = np.array([[1.0]])
        result = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)

        daily_vol = 0.20 / np.sqrt(252)
        z = norm.ppf(0.95)
        expected_es = daily_vol * 100_000 * norm.pdf(z) / (1 - 0.95)
        assert result.expected_shortfall == pytest.approx(expected_es, rel=1e-6)


class TestParametricVaRZeroExposure:
    def test_zero_market_value_portfolio_returns_zero_var(self):
        """When all exposures have zero market value, VaR should be zero, not crash with division by zero."""
        exposures = [AssetClassExposure(AssetClass.EQUITY, 0.0, 0.20)]
        corr = np.array([[1.0]])
        result = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)
        assert result.var_value == 0.0
        assert result.expected_shortfall == 0.0

    def test_all_zero_market_values_multi_asset(self):
        exposures = [
            AssetClassExposure(AssetClass.EQUITY, 0.0, 0.20),
            AssetClassExposure(AssetClass.FX, 0.0, 0.10),
        ]
        corr = np.array([[1.0, 0.5], [0.5, 1.0]])
        result = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, 1, corr)
        assert result.var_value == 0.0


class TestAnalyticExpectedShortfall:
    @pytest.mark.unit
    def test_expected_shortfall_at_99_for_unit_portfolio(self):
        from kinetix_risk.var_parametric import analytic_expected_shortfall_normal
        # ES_99 for unit portfolio, daily sigma 1, T=1: ~2.66 (canonical value).
        es = analytic_expected_shortfall_normal(
            sigma=1.0, confidence=0.99, horizon_days=1, portfolio_value=1.0,
        )
        assert es == pytest.approx(2.66, abs=0.01)

    @pytest.mark.unit
    def test_expected_shortfall_scales_with_sqrt_horizon(self):
        """ES at T=4d should be twice ES at T=1d under sqrt(T) scaling."""
        from kinetix_risk.var_parametric import analytic_expected_shortfall_normal
        es_1d = analytic_expected_shortfall_normal(1.0, 0.99, 1, 1.0)
        es_4d = analytic_expected_shortfall_normal(1.0, 0.99, 4, 1.0)
        assert es_4d == pytest.approx(es_1d * 2.0, rel=1e-6)

    @pytest.mark.unit
    def test_expected_shortfall_rejects_invalid_confidence(self):
        from kinetix_risk.var_parametric import analytic_expected_shortfall_normal
        import pytest
        with pytest.raises(ValueError):
            analytic_expected_shortfall_normal(1.0, 0.0, 1, 1.0)
        with pytest.raises(ValueError):
            analytic_expected_shortfall_normal(1.0, 1.0, 1, 1.0)


class TestModifiedVarCornishFisher:
    @pytest.mark.unit
    def test_modified_var_with_zero_skew_kurt_matches_parametric_var(self):
        """At skew=0 and excess_kurt=0, Cornish-Fisher reduces to the
        standard parametric VaR formula."""
        from kinetix_risk.var_parametric import modified_var_cornish_fisher
        from scipy.stats import norm
        sigma, confidence, T, V0 = 1.0, 0.99, 1, 1.0
        mv = modified_var_cornish_fisher(sigma, 0.0, 0.0, confidence, T, V0)
        standard_var = norm.ppf(confidence) * sigma * V0  # T=1 so sqrt(T)=1
        assert mv == pytest.approx(standard_var, rel=1e-9)

    @pytest.mark.unit
    def test_modified_var_with_fat_tails_exceeds_normal_var(self):
        """Positive excess kurtosis (fat tails) pushes the quantile
        further into the tail -> larger VaR."""
        from kinetix_risk.var_parametric import modified_var_cornish_fisher
        mv_normal = modified_var_cornish_fisher(1.0, 0.0, 0.0, 0.99, 1, 1.0)
        mv_fat = modified_var_cornish_fisher(1.0, 0.0, 3.0, 0.99, 1, 1.0)
        assert mv_fat > mv_normal

    @pytest.mark.unit
    def test_modified_var_rejects_invalid_confidence(self):
        from kinetix_risk.var_parametric import modified_var_cornish_fisher
        with pytest.raises(ValueError):
            modified_var_cornish_fisher(1.0, 0.0, 0.0, 0.0, 1, 1.0)
        with pytest.raises(ValueError):
            modified_var_cornish_fisher(1.0, 0.0, 0.0, 1.0, 1, 1.0)


class TestCornishFisherVar:
    @pytest.mark.unit
    def test_cornish_fisher_var_matches_modified_var(self):
        """The named entry point should produce identical output."""
        from kinetix_risk.var_parametric import cornish_fisher_var, modified_var_cornish_fisher
        a = cornish_fisher_var(1.0, 0.5, 3.0, 0.99, 1, 1.0)
        b = modified_var_cornish_fisher(1.0, 0.5, 3.0, 0.99, 1, 1.0)
        assert a == b

    @pytest.mark.unit
    def test_cornish_fisher_var_with_fat_tail_exceeds_normal(self):
        from kinetix_risk.var_parametric import cornish_fisher_var
        normal = cornish_fisher_var(1.0, 0.0, 0.0, 0.99, 1, 1.0)
        fat = cornish_fisher_var(1.0, 0.0, 5.0, 0.99, 1, 1.0)
        assert fat > normal

    @pytest.mark.unit
    def test_cornish_fisher_var_with_positive_skew_raises_right_tail_quantile(self):
        """Cornish-Fisher is computed on the upper alpha quantile of
        the return distribution; positive skew RAISES that quantile,
        which the helper reports as a larger VaR magnitude. Callers
        wanting the left-tail (loss-of-return) interpretation feed
        in 1-alpha as the confidence argument."""
        from kinetix_risk.var_parametric import cornish_fisher_var
        symmetric = cornish_fisher_var(1.0, 0.0, 0.0, 0.99, 1, 1.0)
        right_skew = cornish_fisher_var(1.0, 1.0, 0.0, 0.99, 1, 1.0)
        assert right_skew > symmetric
