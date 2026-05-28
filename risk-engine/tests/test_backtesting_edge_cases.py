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
