import pytest

from kinetix_risk.models import (
    AssetClass, CalculationType, ConfidenceLevel, PositionRisk,
    PositionStressImpact, StressTestResult,
)
from kinetix_risk.stress.engine import run_stress_test
from kinetix_risk.stress.scenarios import get_scenario
from kinetix_risk.stress.builder import build_hypothetical_scenario


pytestmark = pytest.mark.unit


def _sample_positions() -> list[PositionRisk]:
    return [
        PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD"),
        PositionRisk("GOLD", AssetClass.COMMODITY, 500_000.0, "USD"),
        PositionRisk("UST10Y", AssetClass.FIXED_INCOME, 300_000.0, "USD"),
    ]


class TestStressEngine:
    def test_gfc_produces_higher_var_than_base(self):
        positions = _sample_positions()
        scenario = get_scenario("GFC_2008")
        result = run_stress_test(positions, scenario)
        assert isinstance(result, StressTestResult)
        assert result.stressed_var > result.base_var

    def test_price_shocks_reduce_exposure(self):
        positions = _sample_positions()
        scenario = get_scenario("GFC_2008")
        result = run_stress_test(positions, scenario)
        equity_impact = next(i for i in result.asset_class_impacts if i.asset_class == AssetClass.EQUITY)
        # GFC: equity -40% → stressed exposure is 60% of base
        assert equity_impact.stressed_exposure == pytest.approx(600_000.0)
        assert equity_impact.stressed_exposure < equity_impact.base_exposure

    def test_vol_shocks_increase_var(self):
        positions = [PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD")]
        # Custom scenario: only vol shock, no price shock
        scenario = build_hypothetical_scenario(
            name="vol_only",
            description="Vol shock only",
            vol_shocks={AssetClass.EQUITY: 3.0},
            price_shocks={AssetClass.EQUITY: 1.0},  # no price change
        )
        result = run_stress_test(positions, scenario)
        # Higher vol → higher VaR
        assert result.stressed_var > result.base_var

    def test_stressed_var_uses_custom_correlation(self):
        positions = _sample_positions()
        scenario_with_corr = get_scenario("GFC_2008")  # has correlation override
        scenario_no_corr = get_scenario("TAPER_TANTRUM_2013")  # no correlation override
        result_with = run_stress_test(positions, scenario_with_corr)
        result_without = run_stress_test(positions, scenario_no_corr)
        # Results should differ since correlation matrices differ
        assert result_with.stressed_var != result_without.stressed_var

    def test_pnl_impact_matches_exposure_difference(self):
        positions = _sample_positions()
        scenario = get_scenario("GFC_2008")
        result = run_stress_test(positions, scenario)
        total_base = sum(i.base_exposure for i in result.asset_class_impacts)
        total_stressed = sum(i.stressed_exposure for i in result.asset_class_impacts)
        assert result.pnl_impact == pytest.approx(total_stressed - total_base)

    def test_asset_class_impacts_sum_to_total(self):
        positions = _sample_positions()
        scenario = get_scenario("COVID_2020")
        result = run_stress_test(positions, scenario)
        total_pnl = sum(i.pnl_impact for i in result.asset_class_impacts)
        assert result.pnl_impact == pytest.approx(total_pnl)

    def test_position_impacts_returned_for_each_position(self):
        positions = _sample_positions()
        scenario = get_scenario("GFC_2008")
        result = run_stress_test(positions, scenario)
        assert len(result.position_impacts) == len(positions)
        for pi in result.position_impacts:
            assert isinstance(pi, PositionStressImpact)

    def test_position_impacts_match_price_shocks(self):
        positions = _sample_positions()
        scenario = get_scenario("GFC_2008")
        result = run_stress_test(positions, scenario)
        aapl = next(pi for pi in result.position_impacts if pi.instrument_id == "AAPL")
        assert aapl.base_market_value == 1_000_000.0
        # GFC equity price shock is 0.60
        assert aapl.stressed_market_value == pytest.approx(600_000.0)
        assert aapl.pnl_impact == pytest.approx(-400_000.0)

    def test_position_impacts_percentage_sums_correctly(self):
        positions = _sample_positions()
        scenario = get_scenario("GFC_2008")
        result = run_stress_test(positions, scenario)
        total_pct = sum(pi.percentage_of_total for pi in result.position_impacts)
        # Percentages should sum to ~100% (or more if there are offsetting positions)
        assert total_pct == pytest.approx(100.0, abs=0.1)


class TestHypotheticalBuilder:
    def test_build_custom_scenario(self):
        scenario = build_hypothetical_scenario(
            name="Custom Crisis",
            description="A hypothetical scenario",
            vol_shocks={AssetClass.EQUITY: 2.0},
            price_shocks={AssetClass.EQUITY: 0.70},
        )
        assert scenario.name == "Custom Crisis"
        assert scenario.vol_shocks[AssetClass.EQUITY] == 2.0
        assert scenario.price_shocks[AssetClass.EQUITY] == 0.70
        assert scenario.correlation_override is None

    def test_custom_scenario_runs_through_engine(self):
        positions = [PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD")]
        scenario = build_hypothetical_scenario(
            name="Mild Stress",
            description="Mild equity stress",
            vol_shocks={AssetClass.EQUITY: 1.5},
            price_shocks={AssetClass.EQUITY: 0.90},
        )
        result = run_stress_test(positions, scenario)
        assert isinstance(result, StressTestResult)
        assert result.scenario_name == "Mild Stress"
        assert result.stressed_var > 0
