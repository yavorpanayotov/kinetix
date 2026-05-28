"""Tests for the incremental-VaR helpers."""

import pytest

from kinetix_risk.var_incremental import (
    hedge_sizing_zero_incremental,
    incremental_var,
)


@pytest.mark.unit
def test_incremental_var_positive_for_risk_additive_position():
    assert incremental_var(var_with_position=1_500_000.0, var_without_position=1_000_000.0) == 500_000.0


@pytest.mark.unit
def test_incremental_var_negative_for_hedge_position():
    assert incremental_var(var_with_position=800_000.0, var_without_position=1_000_000.0) == -200_000.0


@pytest.mark.unit
def test_incremental_var_zero_when_position_neutral():
    assert incremental_var(1_000_000.0, 1_000_000.0) == 0


@pytest.mark.unit
def test_incremental_var_rejects_negative_input():
    with pytest.raises(ValueError):
        incremental_var(-1.0, 100.0)
    with pytest.raises(ValueError):
        incremental_var(100.0, -1.0)


@pytest.mark.unit
def test_hedge_sizing_zero_incremental_basic():
    """Base VaR = 100k. A 10k hedge brings VaR to 90k (Δ=-10k per 10k).
    So a 100k hedge would bring incremental to zero (linear extrap)."""
    notional = hedge_sizing_zero_incremental(
        var_base=100_000.0,
        var_with_hedge_unit=90_000.0,
        hedge_unit_notional=10_000.0,
    )
    assert notional == pytest.approx(100_000.0)


@pytest.mark.unit
def test_hedge_sizing_handles_zero_unit_sensitivity():
    """If a hedge unit doesn't move VaR at all, target notional is 0."""
    notional = hedge_sizing_zero_incremental(
        var_base=100_000.0,
        var_with_hedge_unit=100_000.0,
        hedge_unit_notional=10_000.0,
    )
    assert notional == 0.0


@pytest.mark.unit
def test_hedge_sizing_rejects_zero_unit_notional():
    with pytest.raises(ValueError):
        hedge_sizing_zero_incremental(100.0, 90.0, hedge_unit_notional=0.0)
