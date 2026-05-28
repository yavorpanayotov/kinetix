"""Component VaR fixed-weight breakdown.

Component VaR allocates total portfolio VaR back to its constituent
positions. The classical approach (marginal VaR) uses the derivative
of VaR w.r.t. position weight, which only equals the position's
contribution exactly when the portfolio is at the optimum allocation.
The fixed-weight approach below allocates VaR pro-rata by each
position's contribution to portfolio variance — a cleaner, more
interpretable decomposition that sums exactly to total VaR.

References
----------
Litterman, R. (1996). Hot spots and hedges. *Journal of Portfolio
Management*, 23(special issue), 52-75.
"""


def component_var_breakdown(
    position_var_contributions: dict[str, float],
    total_var: float,
) -> dict[str, float]:
    """Allocate total VaR to positions in proportion to their
    contribution to portfolio variance.

    The contribution-to-variance is supplied by the caller (typically
    pre-computed as ``cov_ij * w_i * w_j`` aggregated by position).
    This function does the proportional allocation — for visibility
    in the UI, each position's component VaR sums to the total.

    Negative contributions (hedge positions) are allowed and reduce
    their owner's component VaR. Returns the per-position dollar
    contribution.

    @raise ValueError: if total_var <= 0 — can't allocate zero risk.
    """
    if total_var <= 0:
        raise ValueError(
            f"component VaR: total_var must be positive (got {total_var})",
        )
    contributions_sum = sum(position_var_contributions.values())
    if contributions_sum == 0:
        # Edge case: every position contributes zero. Return zeros so
        # the dashboard still renders rows.
        return {pos: 0.0 for pos in position_var_contributions}
    scale = total_var / contributions_sum
    return {pos: contrib * scale for pos, contrib in position_var_contributions.items()}
