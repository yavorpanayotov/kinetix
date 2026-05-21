from prometheus_client import REGISTRY

from kinetix_risk.metrics import (
    cross_book_diversification_benefit,
    greeks_delta,
    greeks_gamma,
    greeks_rho,
    greeks_theta,
    greeks_vega,
    pnl_attribution_total_pnl,
    pnl_attribution_unexplained_pnl,
    risk_var_calculation_duration_seconds,
    risk_var_calculation_total,
    risk_var_expected_shortfall,
    risk_var_value,
    stress_test_loss,
)
from kinetix_risk.models import (
    AssetClass,
    CalculationType,
    ConfidenceLevel,
    PositionRisk,
)
from kinetix_risk.portfolio_risk import calculate_book_var

import pytest

pytestmark = pytest.mark.unit


def make_equity_position(instrument_id: str, market_value: float) -> PositionRisk:
    return PositionRisk(instrument_id, AssetClass.EQUITY, market_value, "USD")


class TestMetrics:
    def test_counter_increments_after_calculation(self):
        positions = [make_equity_position("AAPL", 100_000.0)]
        before = risk_var_calculation_total.labels(
            calculation_type="PARAMETRIC",
            confidence_level="0.95",
        )._value.get()

        calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )

        after = risk_var_calculation_total.labels(
            calculation_type="PARAMETRIC",
            confidence_level="0.95",
        )._value.get()
        assert after > before

    def test_histogram_has_observations(self):
        positions = [make_equity_position("AAPL", 100_000.0)]
        calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )

        # Collect all samples from the histogram and check _count
        sample_value = None
        for metric in REGISTRY.collect():
            if metric.name == "risk_var_calculation_duration_seconds":
                for sample in metric.samples:
                    if sample.name == "risk_var_calculation_duration_seconds_count":
                        sample_value = sample.value
                        break
        assert sample_value is not None and sample_value > 0

    def test_gauge_is_set_to_var_value(self):
        positions = [make_equity_position("AAPL", 100_000.0)]
        result = calculate_book_var(
            positions, CalculationType.PARAMETRIC, ConfidenceLevel.CL_95, 1,
        )

        risk_var_value.labels(book_id="test-port", calculation_type="PARAMETRIC", confidence_level="CL_95").set(result.var_value)
        assert risk_var_value.labels(book_id="test-port", calculation_type="PARAMETRIC", confidence_level="CL_95")._value.get() == result.var_value

    def test_expected_shortfall_gauge_accepts_three_labels(self):
        risk_var_expected_shortfall.labels(
            book_id="test-port", calculation_type="PARAMETRIC", confidence_level="CL_99",
        ).set(42000.0)
        val = risk_var_expected_shortfall.labels(
            book_id="test-port", calculation_type="PARAMETRIC", confidence_level="CL_99",
        )._value.get()
        assert val == 42000.0


class TestGreeksGauges:
    def test_delta_gauge_accepts_book_and_asset_class(self):
        greeks_delta.labels(book_id="book-1", asset_class="EQUITY").set(0.75)
        assert greeks_delta.labels(book_id="book-1", asset_class="EQUITY")._value.get() == 0.75

    def test_gamma_gauge_accepts_book_and_asset_class(self):
        greeks_gamma.labels(book_id="book-1", asset_class="FX").set(0.02)
        assert greeks_gamma.labels(book_id="book-1", asset_class="FX")._value.get() == 0.02

    def test_vega_gauge_accepts_book_and_asset_class(self):
        greeks_vega.labels(book_id="book-1", asset_class="EQUITY").set(5000.0)
        assert greeks_vega.labels(book_id="book-1", asset_class="EQUITY")._value.get() == 5000.0

    def test_theta_gauge_accepts_book_id(self):
        greeks_theta.labels(book_id="book-1").set(-1200.0)
        assert greeks_theta.labels(book_id="book-1")._value.get() == -1200.0

    def test_rho_gauge_accepts_book_id(self):
        greeks_rho.labels(book_id="book-1").set(3500.0)
        assert greeks_rho.labels(book_id="book-1")._value.get() == 3500.0


class TestPnlGauges:
    def test_total_pnl_gauge_accepts_book_id(self):
        pnl_attribution_total_pnl.labels(book_id="desk-a").set(150000.0)
        assert pnl_attribution_total_pnl.labels(book_id="desk-a")._value.get() == 150000.0

    def test_unexplained_pnl_gauge_accepts_book_id(self):
        pnl_attribution_unexplained_pnl.labels(book_id="desk-a").set(3200.0)
        assert pnl_attribution_unexplained_pnl.labels(book_id="desk-a")._value.get() == 3200.0


class TestCrossBookGauges:
    def test_diversification_benefit_gauge_accepts_portfolio_group_id(self):
        cross_book_diversification_benefit.labels(portfolio_group_id="firm-wide").set(25000.0)
        assert cross_book_diversification_benefit.labels(portfolio_group_id="firm-wide")._value.get() == 25000.0


class TestStressTestLossGauge:
    def test_loss_gauge_accepts_scenario_name_and_book_id_labels(self):
        stress_test_loss.labels(scenario_name="GFC_2008", book_id="desk-a").set(-400000.0)
        assert (
            stress_test_loss.labels(scenario_name="GFC_2008", book_id="desk-a")._value.get()
            == -400000.0
        )

    def test_record_stress_test_loss_sets_gauge_from_result(self):
        from kinetix_risk.models import (
            AssetClass, CalculationType, ConfidenceLevel, PositionRisk,
        )
        from kinetix_risk.stress.engine import run_stress_test
        from kinetix_risk.stress.scenarios import get_scenario
        from kinetix_risk.stress_metrics import record_stress_test_loss

        positions = [PositionRisk("AAPL", AssetClass.EQUITY, 1_000_000.0, "USD")]
        result = run_stress_test(
            positions,
            get_scenario("GFC_2008"),
            CalculationType.PARAMETRIC,
            ConfidenceLevel.CL_95,
            1,
        )

        record_stress_test_loss(result, book_id="desk-stress")

        recorded = stress_test_loss.labels(
            scenario_name=result.scenario_name, book_id="desk-stress",
        )._value.get()
        assert recorded == result.pnl_impact
        # GFC stresses equity down — the recorded P&L impact is a loss.
        assert recorded < 0.0
