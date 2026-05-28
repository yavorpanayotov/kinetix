"""Tests for the component VaR fixed-weight breakdown."""

import pytest

from kinetix_risk.var_component import component_var_breakdown


@pytest.mark.unit
def test_component_var_sums_to_total():
    breakdown = component_var_breakdown(
        position_var_contributions={"AAPL": 30.0, "MSFT": 20.0, "GOOG": 50.0},
        total_var=1_000_000.0,
    )
    assert sum(breakdown.values()) == pytest.approx(1_000_000.0)


@pytest.mark.unit
def test_component_var_proportional_to_contributions():
    breakdown = component_var_breakdown(
        position_var_contributions={"A": 1.0, "B": 3.0},
        total_var=400.0,
    )
    assert breakdown["A"] == pytest.approx(100.0)
    assert breakdown["B"] == pytest.approx(300.0)


@pytest.mark.unit
def test_component_var_allows_negative_hedge_contributions():
    breakdown = component_var_breakdown(
        position_var_contributions={"long": 100.0, "hedge": -25.0},
        total_var=750.0,
    )
    assert breakdown["long"] > 0
    assert breakdown["hedge"] < 0
    assert sum(breakdown.values()) == pytest.approx(750.0)


@pytest.mark.unit
def test_component_var_rejects_zero_total_var():
    with pytest.raises(ValueError):
        component_var_breakdown({"A": 1.0}, total_var=0.0)


@pytest.mark.unit
def test_component_var_handles_all_zero_contributions():
    breakdown = component_var_breakdown(
        position_var_contributions={"A": 0.0, "B": 0.0},
        total_var=100.0,
    )
    assert breakdown == {"A": 0.0, "B": 0.0}
