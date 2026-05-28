

def find_worst_scenario_for_portfolio(
    scenario_losses: dict[str, float],
) -> tuple[str, float]:
    """Reverse stress: return the (scenario, loss) tuple of the
    worst-loss scenario for the current portfolio.

    Forward stress takes a scenario and computes the loss; reverse
    stress takes a loss threshold and asks "which scenario produces
    it" — same data, different lookup direction. The simplest
    reverse-stress optimisation is to scan the precomputed scenario
    losses and return the max.

    Empty input returns ("", 0.0) — there's no worst scenario for
    an empty stress run.
    """
    if not scenario_losses:
        return ("", 0.0)
    worst = max(scenario_losses.items(), key=lambda item: item[1])
    return worst


def rank_scenarios_by_loss(
    scenario_losses: dict[str, float],
) -> list[tuple[str, float]]:
    """Rank scenarios from worst (highest loss) to best (lowest).
    Useful for the regulatory submission that wants a top-N table."""
    return sorted(scenario_losses.items(), key=lambda item: -item[1])
