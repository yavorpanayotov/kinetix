import numpy as np
import pytest

from kinetix_risk.backtesting import run_backtest
from kinetix_risk.models import BacktestResult, TrafficLightZone


pytestmark = pytest.mark.unit


class TestBacktestViolationCounting:
    def test_backtest_counts_var_violations(self):
        # VaR predictions are 100 each day, actual losses exceed on 3 days
        daily_var = [100.0] * 10
        daily_pnl = [-50.0, -150.0, -80.0, -200.0, -90.0, -110.0, -30.0, -70.0, -60.0, -40.0]
        # Violations: day 1 (loss 150 > 100), day 3 (loss 200 > 100), day 5 (loss 110 > 100)
        result = run_backtest(daily_var, daily_pnl, confidence_level=0.99)
        assert result.violation_count == 3

    def test_backtest_no_violations_when_var_conservative(self):
        # VaR predictions are very large; no actual loss exceeds them
        daily_var = [10000.0] * 20
        daily_pnl = [-50.0] * 20
        result = run_backtest(daily_var, daily_pnl, confidence_level=0.99)
        assert result.violation_count == 0
        assert result.violation_rate == 0.0


class TestKupiecPofTest:
    def test_kupiec_pof_test_passes_for_expected_violation_rate(self):
        # At 99% confidence over 250 days, expected violations ≈ 2.5
        # Simulate exactly 3 violations which is close to expected
        rng = np.random.default_rng(42)
        n_days = 250
        daily_var = [100.0] * n_days
        daily_pnl = [-50.0] * n_days
        # Place exactly 3 violations
        violation_indices = [10, 80, 200]
        for i in violation_indices:
            daily_pnl[i] = -150.0

        result = run_backtest(daily_var, daily_pnl, confidence_level=0.99)
        assert result.kupiec_pass is True
        assert result.kupiec_p_value > 0.05

    def test_kupiec_pof_test_fails_for_excessive_violations(self):
        # 25 violations out of 250 days = 10% violation rate vs 1% expected
        n_days = 250
        daily_var = [100.0] * n_days
        daily_pnl = [-50.0] * n_days
        for i in range(25):
            daily_pnl[i * 10] = -150.0

        result = run_backtest(daily_var, daily_pnl, confidence_level=0.99)
        assert result.kupiec_pass is False
        assert result.kupiec_p_value < 0.05


class TestChristoffersenIndependenceTest:
    def test_christoffersen_independence_test(self):
        # Clustered violations should fail independence
        n_days = 250
        daily_var = [100.0] * n_days
        daily_pnl = [-50.0] * n_days
        # Cluster 10 violations in consecutive days
        for i in range(10):
            daily_pnl[100 + i] = -150.0

        result = run_backtest(daily_var, daily_pnl, confidence_level=0.99)
        # Clustered violations should fail independence test
        assert result.christoffersen_pass is False


class TestTrafficLightZones:
    def test_traffic_light_green_zone(self):
        # 0-4 violations at 99% over 250 days = GREEN
        n_days = 250
        daily_var = [100.0] * n_days
        daily_pnl = [-50.0] * n_days
        # Place 3 violations (spread out)
        for i in [50, 120, 200]:
            daily_pnl[i] = -150.0

        result = run_backtest(daily_var, daily_pnl, confidence_level=0.99)
        assert result.traffic_light_zone == TrafficLightZone.GREEN

    def test_traffic_light_yellow_zone(self):
        # 5-9 violations at 99% over 250 days = YELLOW
        n_days = 250
        daily_var = [100.0] * n_days
        daily_pnl = [-50.0] * n_days
        # Place 7 violations (spread out)
        for i in [20, 55, 90, 125, 160, 195, 230]:
            daily_pnl[i] = -150.0

        result = run_backtest(daily_var, daily_pnl, confidence_level=0.99)
        assert result.traffic_light_zone == TrafficLightZone.YELLOW

    def test_traffic_light_red_zone(self):
        # 10+ violations at 99% over 250 days = RED
        n_days = 250
        daily_var = [100.0] * n_days
        daily_pnl = [-50.0] * n_days
        # Place 12 violations (spread out)
        for i in range(12):
            daily_pnl[i * 20] = -150.0

        result = run_backtest(daily_var, daily_pnl, confidence_level=0.99)
        assert result.traffic_light_zone == TrafficLightZone.RED


class TestTrafficLightBoundaryValues:
    """Boundary tests for the exact cutoff values: 4/5 (GREEN/YELLOW) and 9/10 (YELLOW/RED)."""

    def _run_with_n_violations(self, n_violations: int) -> BacktestResult:
        n_days = 250
        daily_var = [100.0] * n_days
        daily_pnl = [-50.0] * n_days
        for i in range(n_violations):
            daily_pnl[i * (n_days // max(n_violations, 1))] = -150.0
        return run_backtest(daily_var, daily_pnl, confidence_level=0.99)

    def test_exactly_4_violations_is_green(self):
        """4 violations is the last GREEN value (boundary: <= 4)."""
        result = self._run_with_n_violations(4)
        assert result.violation_count == 4
        assert result.traffic_light_zone == TrafficLightZone.GREEN

    def test_exactly_5_violations_is_yellow(self):
        """5 violations is the first YELLOW value (boundary: 5-9)."""
        result = self._run_with_n_violations(5)
        assert result.violation_count == 5
        assert result.traffic_light_zone == TrafficLightZone.YELLOW

    def test_exactly_9_violations_is_yellow(self):
        """9 violations is the last YELLOW value (boundary: 5-9)."""
        result = self._run_with_n_violations(9)
        assert result.violation_count == 9
        assert result.traffic_light_zone == TrafficLightZone.YELLOW

    def test_exactly_10_violations_is_red(self):
        """10 violations is the first RED value (boundary: >= 10)."""
        result = self._run_with_n_violations(10)
        assert result.violation_count == 10
        assert result.traffic_light_zone == TrafficLightZone.RED


class TestBacktestResultCompleteness:
    def test_backtest_result_contains_all_metrics(self):
        n_days = 250
        daily_var = [100.0] * n_days
        daily_pnl = [-50.0] * n_days
        daily_pnl[50] = -150.0  # 1 violation

        result = run_backtest(daily_var, daily_pnl, confidence_level=0.99)

        assert isinstance(result, BacktestResult)
        assert result.total_days == 250
        assert result.violation_count == 1
        assert result.violation_rate == pytest.approx(1 / 250)
        assert result.expected_violation_rate == pytest.approx(0.01)
        assert isinstance(result.kupiec_statistic, float)
        assert isinstance(result.kupiec_p_value, float)
        assert isinstance(result.kupiec_pass, bool)
        assert isinstance(result.christoffersen_statistic, float)
        assert isinstance(result.christoffersen_p_value, float)
        assert isinstance(result.christoffersen_pass, bool)
        assert result.traffic_light_zone in TrafficLightZone
        assert isinstance(result.violations, list)
        assert len(result.violations) == 1
        assert result.violations[0]["day_index"] == 50
        assert result.violations[0]["var_value"] == 100.0
        assert result.violations[0]["actual_pnl"] == -150.0
