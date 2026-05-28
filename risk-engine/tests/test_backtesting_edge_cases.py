"""Edge-case tests for the VaR backtester at 0% and 100% violation rates.

The 0% case: a deeply conservative VaR model that no daily P&L ever
exceeds. The Kupiec POF test should flag this as well outside the
expected violation rate (model is over-stating risk). The 100% case:
a degenerate model whose VaR is always exceeded — should flag
violently. Neither case should crash the backtester.
"""

import pytest


@pytest.mark.unit
def test_backtest_zero_violations_does_not_crash():
    from kinetix_risk.backtesting import run_backtest

    days = 252  # 1 trading year
    # VaR always 1000; P&L always +500 (a gain — actual_loss = -500 < VaR).
    result = run_backtest(
        daily_var_predictions=[1000.0] * days,
        daily_pnl=[500.0] * days,
        confidence_level=0.99,
    )
    assert result.violation_count == 0
    assert result.violation_rate == 0.0


@pytest.mark.unit
def test_backtest_all_violations_does_not_crash():
    from kinetix_risk.backtesting import run_backtest

    days = 252
    # VaR always 100, P&L always -1000 (actual_loss = 1000 > VaR).
    result = run_backtest(
        daily_var_predictions=[100.0] * days,
        daily_pnl=[-1000.0] * days,
        confidence_level=0.99,
    )
    assert result.violation_count == days
    assert result.violation_rate == 1.0


@pytest.mark.unit
def test_backtest_single_day_at_each_extreme():
    from kinetix_risk.backtesting import run_backtest

    # One-day backtest with a violation.
    r_violated = run_backtest([100.0], [-200.0], confidence_level=0.95)
    assert r_violated.violation_count == 1

    # One-day backtest without a violation.
    r_clean = run_backtest([100.0], [50.0], confidence_level=0.95)
    assert r_clean.violation_count == 0


# kx-6iz — Basel traffic-light amber-zone multiplier
@pytest.mark.unit
def test_basel_traffic_light_green_zone_multiplier_is_zero():
    from kinetix_risk.backtesting import basel_traffic_light_multiplier
    for count in range(5):
        assert basel_traffic_light_multiplier(count) == 0.0


@pytest.mark.unit
def test_basel_traffic_light_red_zone_multiplier_is_one():
    from kinetix_risk.backtesting import basel_traffic_light_multiplier
    for count in (10, 15, 30):
        assert basel_traffic_light_multiplier(count) == 1.0


@pytest.mark.unit
def test_basel_traffic_light_amber_zone_monotonic():
    """The amber multipliers increase monotonically as exception count rises."""
    from kinetix_risk.backtesting import basel_traffic_light_multiplier
    values = [basel_traffic_light_multiplier(c) for c in range(5, 10)]
    for i in range(len(values) - 1):
        assert values[i] < values[i + 1]


@pytest.mark.unit
def test_basel_traffic_light_amber_band_boundary_values():
    from kinetix_risk.backtesting import basel_traffic_light_multiplier
    assert basel_traffic_light_multiplier(5) == 0.40
    assert basel_traffic_light_multiplier(9) == 0.85


# kx-66s — VaR overshoot magnitude tracking
@pytest.mark.unit
def test_overshoot_magnitude_no_breaches_returns_zero():
    from kinetix_risk.backtesting import overshoot_magnitude_summary
    s = overshoot_magnitude_summary([100.0] * 252, [50.0] * 252)  # all gains
    assert s["overshoot_count"] == 0
    assert s["mean_overshoot"] == 0
    assert s["max_overshoot"] == 0
    assert s["total_overshoot"] == 0


@pytest.mark.unit
def test_overshoot_magnitude_captures_excess_loss():
    from kinetix_risk.backtesting import overshoot_magnitude_summary
    # VaR=100, P&L=-150 on day 0 -> actual_loss=150, overshoot=50.
    # Day 1: VaR=100, P&L=-200 -> overshoot=100.
    s = overshoot_magnitude_summary([100.0, 100.0], [-150.0, -200.0])
    assert s["overshoot_count"] == 2
    assert s["mean_overshoot"] == 75.0
    assert s["max_overshoot"] == 100.0
    assert s["total_overshoot"] == 150.0


@pytest.mark.unit
def test_overshoot_magnitude_ignores_non_breach_days():
    from kinetix_risk.backtesting import overshoot_magnitude_summary
    # First day breaches by 50; second day is a gain (no breach).
    s = overshoot_magnitude_summary([100.0, 100.0], [-150.0, 50.0])
    assert s["overshoot_count"] == 1
    assert s["mean_overshoot"] == 50.0


# kx-msy — rolling 252-day window backtest
@pytest.mark.unit
def test_rolling_window_short_sample_returns_empty():
    from kinetix_risk.backtesting import rolling_window_violation_counts
    # 100 days, window 252 -> empty.
    assert rolling_window_violation_counts([100.0]*100, [50.0]*100) == []


@pytest.mark.unit
def test_rolling_window_returns_one_count_per_window_position():
    from kinetix_risk.backtesting import rolling_window_violation_counts
    # 500 days, window 252 -> 500 - 252 + 1 = 249 windows.
    counts = rolling_window_violation_counts([100.0]*500, [50.0]*500, window_days=252)
    assert len(counts) == 249
    # No breaches anywhere (50 < 100).
    assert all(c == 0 for c in counts)


@pytest.mark.unit
def test_rolling_window_captures_drift():
    """The first window (all in the past) has fewer breaches than the
    last window (which contains the recent regime break)."""
    from kinetix_risk.backtesting import rolling_window_violation_counts
    # 300 days: first 252 are clean (P&L=50), last 48 are breaches (P&L=-300).
    var_preds = [100.0] * 300
    pnl = [50.0] * 252 + [-300.0] * 48
    counts = rolling_window_violation_counts(var_preds, pnl, window_days=252)
    assert counts[0] == 0
    assert counts[-1] == 48
