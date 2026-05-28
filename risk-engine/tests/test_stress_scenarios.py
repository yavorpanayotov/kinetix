import numpy as np
import pytest

from kinetix_risk.models import AssetClass, StressScenario
from kinetix_risk.stress.scenarios import (
    HISTORICAL_SCENARIOS, ScenarioCategory, get_scenario, list_scenarios,
)
from kinetix_risk.stress.parametric_grid import generate_parametric_grid


class TestHistoricalScenarios:
    def test_gfc_2008_exists_with_expected_shocks(self):
        scenario = get_scenario("GFC_2008")
        assert scenario.name == "GFC_2008"
        assert scenario.price_shocks[AssetClass.EQUITY] == 0.60  # 40% drop
        assert scenario.price_shocks[AssetClass.COMMODITY] == 0.70  # 30% drop
        assert scenario.vol_shocks[AssetClass.EQUITY] == 3.0  # vol x3
        assert scenario.correlation_override is not None

    def test_covid_2020_has_equity_vol_shock(self):
        scenario = get_scenario("COVID_2020")
        assert scenario.vol_shocks[AssetClass.EQUITY] == 2.5
        assert scenario.price_shocks[AssetClass.EQUITY] == 0.65  # 35% drop
        assert scenario.price_shocks[AssetClass.COMMODITY] == 0.75  # 25% drop
        assert scenario.correlation_override is not None

    def test_all_scenarios_have_valid_structure(self):
        for name, scenario in HISTORICAL_SCENARIOS.items():
            assert isinstance(scenario, StressScenario)
            assert scenario.name == name
            assert len(scenario.description) > 0
            assert len(scenario.vol_shocks) > 0
            assert len(scenario.price_shocks) > 0
            for shock in scenario.vol_shocks.values():
                assert shock >= 1.0, f"Vol shock in {name} should be >= 1.0 (multiplicative)"
            for shock in scenario.price_shocks.values():
                assert shock > 0.0, f"Price shock in {name} should be > 0 (multiplicative)"
            if scenario.correlation_override is not None:
                assert scenario.correlation_override.shape == (5, 5)
                np.testing.assert_array_almost_equal(
                    np.diag(scenario.correlation_override), np.ones(5),
                )

    def test_all_scenarios_have_category(self):
        for name, scenario in HISTORICAL_SCENARIOS.items():
            assert isinstance(scenario.category, ScenarioCategory), (
                f"Scenario {name} must have a ScenarioCategory"
            )

    def test_get_unknown_scenario_raises(self):
        with pytest.raises(KeyError, match="Unknown stress scenario"):
            get_scenario("NONEXISTENT_CRISIS")

    def test_list_scenarios_returns_all_names(self):
        names = list_scenarios()
        assert "GFC_2008" in names
        assert "COVID_2020" in names
        assert "TAPER_TANTRUM_2013" in names
        assert "EURO_CRISIS_2011" in names
        # Library now contains at least 15 scenarios
        assert len(names) >= 15

    # --- New scenarios: specific shock checks ---

    def test_black_monday_1987_has_22_percent_equity_drop(self):
        scenario = get_scenario("BLACK_MONDAY_1987")
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.78)
        assert scenario.vol_shocks[AssetClass.EQUITY] == pytest.approx(4.0)
        assert scenario.price_shocks[AssetClass.FIXED_INCOME] == pytest.approx(1.02)
        assert scenario.price_shocks[AssetClass.FX] == pytest.approx(0.95)

    def test_ltcm_1998_has_15_percent_equity_drop_and_vol_spike(self):
        scenario = get_scenario("LTCM_RUSSIAN_1998")
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.85)
        assert scenario.vol_shocks[AssetClass.EQUITY] == pytest.approx(2.5)
        assert scenario.price_shocks[AssetClass.FIXED_INCOME] == pytest.approx(0.95)
        assert scenario.price_shocks[AssetClass.FX] == pytest.approx(0.90)

    def test_dotcom_2000_has_25_percent_equity_drop(self):
        scenario = get_scenario("DOTCOM_2000")
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.75)
        assert scenario.vol_shocks[AssetClass.EQUITY] == pytest.approx(2.0)
        assert scenario.price_shocks[AssetClass.FIXED_INCOME] == pytest.approx(1.03)

    def test_sept_11_has_equity_drop_and_flight_to_quality(self):
        scenario = get_scenario("SEPT_11_2001")
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.88)
        assert scenario.vol_shocks[AssetClass.EQUITY] == pytest.approx(3.0)
        assert scenario.price_shocks[AssetClass.FIXED_INCOME] == pytest.approx(1.05)

    def test_chf_depeg_2015_has_large_fx_shock(self):
        scenario = get_scenario("CHF_DEPEG_2015")
        # CHF appreciated ~30% — modelled as FX shock > 1 (base currency appreciates)
        assert scenario.price_shocks[AssetClass.FX] == pytest.approx(1.30)
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.95)
        assert scenario.vol_shocks[AssetClass.EQUITY] == pytest.approx(2.0)

    def test_brexit_2016_has_gbp_drop_via_fx(self):
        scenario = get_scenario("BREXIT_2016")
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.92)
        assert scenario.price_shocks[AssetClass.FX] == pytest.approx(0.90)
        assert scenario.vol_shocks[AssetClass.EQUITY] == pytest.approx(2.0)

    def test_volmageddon_2018_has_extreme_vol_shock(self):
        scenario = get_scenario("VOLMAGEDDON_2018")
        assert scenario.vol_shocks[AssetClass.EQUITY] == pytest.approx(5.0)
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.90)

    def test_oil_negative_2020_has_commodity_shock(self):
        scenario = get_scenario("OIL_NEGATIVE_2020")
        # WTI briefly went negative — modelled as near-total commodity loss
        assert scenario.price_shocks[AssetClass.COMMODITY] == pytest.approx(0.01)
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.95)

    def test_svb_banking_2023_has_equity_and_fixed_income_impact(self):
        scenario = get_scenario("SVB_BANKING_2023")
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.90)
        assert scenario.price_shocks[AssetClass.FIXED_INCOME] == pytest.approx(0.97)
        assert scenario.vol_shocks[AssetClass.EQUITY] == pytest.approx(2.0)

    def test_rates_shock_2022_has_fixed_income_and_equity_decline(self):
        scenario = get_scenario("RATES_SHOCK_2022")
        assert scenario.price_shocks[AssetClass.FIXED_INCOME] == pytest.approx(0.85)
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.90)
        assert scenario.vol_shocks[AssetClass.EQUITY] == pytest.approx(1.5)

    def test_em_contagion_has_em_equity_and_fx_drop(self):
        scenario = get_scenario("EM_CONTAGION")
        assert scenario.price_shocks[AssetClass.EQUITY] == pytest.approx(0.80)
        assert scenario.price_shocks[AssetClass.FX] == pytest.approx(0.85)
        assert scenario.vol_shocks[AssetClass.EQUITY] == pytest.approx(2.5)

    # --- Category assignments ---

    def test_gfc_2008_is_regulatory_mandated(self):
        assert get_scenario("GFC_2008").category == ScenarioCategory.REGULATORY_MANDATED

    def test_covid_2020_is_regulatory_mandated(self):
        assert get_scenario("COVID_2020").category == ScenarioCategory.REGULATORY_MANDATED

    def test_black_monday_1987_is_regulatory_mandated(self):
        assert get_scenario("BLACK_MONDAY_1987").category == ScenarioCategory.REGULATORY_MANDATED

    def test_volmageddon_2018_is_internal_approved(self):
        assert get_scenario("VOLMAGEDDON_2018").category == ScenarioCategory.INTERNAL_APPROVED

    def test_em_contagion_is_internal_approved(self):
        assert get_scenario("EM_CONTAGION").category == ScenarioCategory.INTERNAL_APPROVED

    # --- Canned demo scenario: +100bps parallel rates shock (kx-wxy) ---

    def test_plus_100bps_parallel_is_registered(self):
        scenario = get_scenario("+100BPS_PARALLEL")
        assert scenario.name == "+100BPS_PARALLEL"
        assert "100bps" in scenario.description.lower() or "+100bp" in scenario.description.lower()

    def test_plus_100bps_parallel_drops_fixed_income_via_8y_duration(self):
        # +100bp parallel shift on an 8y-duration FI book → ~-8% price impact, i.e. price_shock ≈ 0.92.
        scenario = get_scenario("+100BPS_PARALLEL")
        assert scenario.price_shocks[AssetClass.FIXED_INCOME] == pytest.approx(0.92)

    def test_plus_100bps_parallel_is_internal_approved(self):
        assert get_scenario("+100BPS_PARALLEL").category == ScenarioCategory.INTERNAL_APPROVED

    def test_plus_100bps_parallel_listed_among_scenarios(self):
        assert "+100BPS_PARALLEL" in list_scenarios()


class TestParametricGrid:
    @pytest.mark.unit
    def test_equity_vol_grid_produces_correct_number_of_scenarios(self):
        equity_range = [-0.30, -0.20, -0.10, 0.0, 0.10]
        vol_range = [1.0, 2.0, 3.0, 4.0]
        scenarios = generate_parametric_grid(
            primary_axis="equity",
            primary_range=equity_range,
            secondary_axis="vol",
            secondary_range=vol_range,
        )
        assert len(scenarios) == len(equity_range) * len(vol_range)  # 20 scenarios

    @pytest.mark.unit
    def test_rates_credit_grid_produces_correct_number_of_scenarios(self):
        rates_range = [-0.03, -0.02, -0.01, 0.0, 0.01]
        credit_range = [0.0, 0.005, 0.01, 0.02]
        scenarios = generate_parametric_grid(
            primary_axis="rates",
            primary_range=rates_range,
            secondary_axis="credit",
            secondary_range=credit_range,
        )
        assert len(scenarios) == len(rates_range) * len(credit_range)  # 20 scenarios

    @pytest.mark.unit
    def test_all_grid_scenarios_are_valid_stress_scenarios(self):
        scenarios = generate_parametric_grid(
            primary_axis="equity",
            primary_range=[-0.20, -0.10, 0.0],
            secondary_axis="vol",
            secondary_range=[1.0, 2.0],
        )
        for scenario in scenarios:
            assert isinstance(scenario, StressScenario)
            assert len(scenario.name) > 0
            assert len(scenario.description) > 0
            assert len(scenario.vol_shocks) > 0

    @pytest.mark.unit
    def test_equity_grid_scenario_names_encode_shock_values(self):
        scenarios = generate_parametric_grid(
            primary_axis="equity",
            primary_range=[-0.20],
            secondary_axis="vol",
            secondary_range=[2.0],
        )
        assert len(scenarios) == 1
        assert "equity" in scenarios[0].name.lower() or "EQUITY" in scenarios[0].name

    @pytest.mark.unit
    def test_grid_price_shocks_reflect_primary_range(self):
        """A -20% equity shock should produce price_shock of 0.80."""
        scenarios = generate_parametric_grid(
            primary_axis="equity",
            primary_range=[-0.20],
            secondary_axis="vol",
            secondary_range=[1.0],
        )
        assert len(scenarios) == 1
        assert scenarios[0].price_shocks[AssetClass.EQUITY] == pytest.approx(0.80)

    @pytest.mark.unit
    def test_grid_vol_shocks_reflect_secondary_range(self):
        scenarios = generate_parametric_grid(
            primary_axis="equity",
            primary_range=[-0.10],
            secondary_axis="vol",
            secondary_range=[3.0],
        )
        assert scenarios[0].vol_shocks[AssetClass.EQUITY] == pytest.approx(3.0)

    @pytest.mark.unit
    def test_grid_scenarios_have_parametric_category(self):
        scenarios = generate_parametric_grid(
            primary_axis="equity",
            primary_range=[-0.10],
            secondary_axis="vol",
            secondary_range=[2.0],
        )
        assert scenarios[0].category == ScenarioCategory.INTERNAL_APPROVED

    @pytest.mark.unit
    def test_default_equity_vol_grid_produces_20_scenarios(self):
        from kinetix_risk.stress.parametric_grid import default_equity_vol_grid
        scenarios = default_equity_vol_grid()
        assert len(scenarios) == 20  # 5 equity levels x 4 vol multipliers

    @pytest.mark.unit
    def test_default_rates_credit_grid_produces_20_scenarios(self):
        from kinetix_risk.stress.parametric_grid import default_rates_credit_grid
        scenarios = default_rates_credit_grid()
        assert len(scenarios) == 20  # 5 rates levels x 4 credit spread levels


# kx-k6p — historical-replay -> backtester integration hook
class TestHistoricalReplayHook:
    @pytest.mark.unit
    def test_historical_replay_loss_below_var_is_not_breach(self):
        from kinetix_risk.historical_replay import HistoricalReplayResult
        r = HistoricalReplayResult("2008-09-15", hypothetical_pnl=-50.0, portfolio_market_value=1000.0)
        assert r.is_var_breach(var_value=100.0) is False
        assert r.hypothetical_loss() == 50.0

    @pytest.mark.unit
    def test_historical_replay_loss_above_var_is_breach(self):
        from kinetix_risk.historical_replay import HistoricalReplayResult
        r = HistoricalReplayResult("2020-03-12", hypothetical_pnl=-200.0, portfolio_market_value=1000.0)
        assert r.is_var_breach(var_value=100.0) is True

    @pytest.mark.unit
    def test_historical_replay_gain_is_never_breach(self):
        from kinetix_risk.historical_replay import HistoricalReplayResult
        r = HistoricalReplayResult("2021-01-01", hypothetical_pnl=+200.0, portfolio_market_value=1000.0)
        assert r.is_var_breach(var_value=100.0) is False
        assert r.hypothetical_loss() == 0.0

    @pytest.mark.unit
    def test_historical_replay_summary_counts_breaches(self):
        from kinetix_risk.historical_replay import HistoricalReplayResult, assemble_replay_breach_summary
        replays = [
            HistoricalReplayResult("2008-09-15", -200.0, 1000.0),
            HistoricalReplayResult("2020-03-12", -300.0, 1000.0),
            HistoricalReplayResult("2018-02-05", -50.0, 1000.0),
        ]
        summary = assemble_replay_breach_summary(replays, var_value=100.0)
        assert summary["total_replays"] == 3
        assert summary["breach_count"] == 2
        assert summary["breach_rate"] == 2 / 3
        assert summary["worst_loss"] == 300.0

    @pytest.mark.unit
    def test_historical_replay_summary_empty(self):
        from kinetix_risk.historical_replay import assemble_replay_breach_summary
        summary = assemble_replay_breach_summary([], var_value=100.0)
        assert summary["total_replays"] == 0
        assert summary["breach_count"] == 0
        assert summary["breach_rate"] == 0.0
        assert summary["worst_loss"] == 0.0
