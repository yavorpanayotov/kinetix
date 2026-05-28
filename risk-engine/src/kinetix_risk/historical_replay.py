"""Historical replay integration hook for the backtesting module.

A historical replay reuses an actual past return series (2008-09-15,
2020-03-12, etc.) as the input to today's portfolio P&L calculation,
producing a hypothetical loss that says "if this exact day happened
again, the current book would lose X". The hook below is the seam
that lets the backtester consume that signal alongside its forward-
looking VaR predictions, so a replay can be compared against the
model's stated tolerance for tail losses.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class HistoricalReplayResult:
    """Output of a single historical-day replay against today's book."""

    scenario_date: str
    hypothetical_pnl: float
    portfolio_market_value: float

    def hypothetical_loss(self) -> float:
        """Returns the loss as a positive number (or 0 for a gain)."""
        return max(0.0, -self.hypothetical_pnl)

    def is_var_breach(self, var_value: float) -> bool:
        """True if the replay loss exceeds the supplied VaR."""
        return self.hypothetical_loss() > var_value


def assemble_replay_breach_summary(
    replays: list[HistoricalReplayResult],
    var_value: float,
) -> dict[str, object]:
    """Count VaR breaches across a list of historical-replay results."""
    total = len(replays)
    breaches = [r for r in replays if r.is_var_breach(var_value)]
    worst_loss = max((r.hypothetical_loss() for r in replays), default=0.0)
    return {
        "total_replays": total,
        "breach_count": len(breaches),
        "breach_rate": len(breaches) / total if total > 0 else 0.0,
        "worst_loss": worst_loss,
    }
