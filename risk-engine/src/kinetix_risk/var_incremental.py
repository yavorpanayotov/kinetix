"""Incremental VaR — risk impact of adding/removing a single position.

Incremental VaR answers "if I add this trade, how much does the
portfolio's VaR change?" — a forward-looking what-if analysis that
sits between Marginal VaR (the derivative at the current allocation)
and Component VaR (the historical attribution).

The calculation is the difference between two VaR computations:
with-position vs without-position. Useful pre-trade for sizing a
risk-additive entry, and for setting a hedge size that brings
incremental risk to zero.
"""


def incremental_var(var_with_position: float, var_without_position: float) -> float:
    """Difference between portfolio VaR with and without a candidate
    position. Positive means the position adds risk (a long-only
    addition usually does); negative means the position is a hedge.

    @raise ValueError: if either input is negative — VaR is a positive
    quantity by definition.
    """
    if var_with_position < 0 or var_without_position < 0:
        raise ValueError("VaR values must be non-negative")
    return var_with_position - var_without_position


def hedge_sizing_zero_incremental(
    var_base: float,
    var_with_hedge_unit: float,
    hedge_unit_notional: float,
) -> float:
    """Solve for the hedge notional that drives incremental VaR to
    zero, assuming linear scaling: ``Δ_VaR / Δ_notional`` is
    constant in the range we care about.

    The first-order approximation:
        Δ_VaR per unit = (var_with_hedge_unit - var_base) / hedge_unit
        target_notional = var_base / (Δ_VaR per unit) * (-1)

    Returns the notional size of the hedge (in same units as
    hedge_unit_notional) that makes incremental VaR exactly zero in
    the linear approximation.
    """
    if hedge_unit_notional == 0:
        raise ValueError("hedge_unit_notional must be non-zero")
    delta_var_per_unit = (var_with_hedge_unit - var_base) / hedge_unit_notional
    if delta_var_per_unit == 0:
        return 0.0
    return -var_base / delta_var_per_unit
