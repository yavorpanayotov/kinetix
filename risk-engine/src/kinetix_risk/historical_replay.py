"""Historical replay integration hook for the backtesting module.

A historical replay reuses an actual past return series (2008-09-15,
2020-03-12, etc.) as the input to today's portfolio P&L calculation,
producing a hypothetical loss that says "if this exact day happened
again, the current book would lose X". The hook below is the seam
that lets the backtester consume that signal alongside its forward-
looking VaR predictions, so a replay can be compared against the
model's stated tolerance for tail losses.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

import numpy as np

from kinetix_risk.models import AssetClass, PositionRisk

# ---------------------------------------------------------------------------
# Proxy returns: asset-class level fallback for instruments with no history.
# These are synthetic 5-day return series representative of a moderate stress
# event. Real deployments should replace these with regime-specific vectors.
# ---------------------------------------------------------------------------

ASSET_CLASS_PROXY_RETURNS: dict[AssetClass, np.ndarray] = {
    AssetClass.EQUITY: np.array([-0.02, -0.03, -0.01, -0.015, -0.005], dtype=float),
    AssetClass.FIXED_INCOME: np.array([0.003, -0.002, 0.001, -0.001, 0.002], dtype=float),
    AssetClass.FX: np.array([-0.005, 0.002, -0.003, 0.001, -0.002], dtype=float),
    AssetClass.COMMODITY: np.array([-0.015, -0.02, 0.005, -0.01, -0.008], dtype=float),
    AssetClass.DERIVATIVE: np.array([-0.025, -0.035, -0.01, -0.02, -0.005], dtype=float),
}


# ---------------------------------------------------------------------------
# Domain types
# ---------------------------------------------------------------------------


@dataclass
class HistoricalReplayRequest:
    """Parameters for a historical replay calculation."""

    scenario_name: str
    positions: list[PositionRisk]
    instrument_returns: dict[str, np.ndarray]
    window_start: Optional[str] = None
    window_end: Optional[str] = None


@dataclass
class PositionReplayImpact:
    """P&L impact of the replay scenario on a single position."""

    instrument_id: str
    asset_class: AssetClass
    market_value: float
    pnl_impact: float
    daily_pnl: list[float]
    proxy_used: bool


@dataclass
class HistoricalReplayResult:
    """Simple single-day replay result used by the backtesting hook.

    This is the lightweight form used for backtesting VaR breach counting.
    See ``HistoricalReplayRunResult`` for the full position-level form
    returned by ``run_historical_replay``.
    """

    scenario_date: str
    hypothetical_pnl: float
    portfolio_market_value: float

    def hypothetical_loss(self) -> float:
        """Returns the loss as a positive number (or 0 for a gain)."""
        return max(0.0, -self.hypothetical_pnl)

    def is_var_breach(self, var_value: float) -> bool:
        """True if the replay loss exceeds the supplied VaR."""
        return self.hypothetical_loss() > var_value


@dataclass
class HistoricalReplayRunResult:
    """Full position-level result returned by ``run_historical_replay``.

    Contains per-position impacts, daily P&L breakdowns, and metadata
    about the replay window and proxy usage.
    """

    scenario_name: str
    total_pnl_impact: float
    position_impacts: list[PositionReplayImpact]
    window_start: Optional[str] = None
    window_end: Optional[str] = None


# ---------------------------------------------------------------------------
# Calculation
# ---------------------------------------------------------------------------


def run_historical_replay(request: HistoricalReplayRequest) -> HistoricalReplayRunResult:
    """Apply historical daily returns to the current book to produce hypothetical P&L.

    For each position, the cumulative P&L is sum(return_i * market_value) across
    all days. Instruments with no entry in ``instrument_returns`` fall back to the
    asset-class proxy series and are flagged ``proxy_used=True``.

    Raises:
        ValueError: If positions list is empty.
    """
    if not request.positions:
        raise ValueError("positions must not be empty for historical replay")

    impacts: list[PositionReplayImpact] = []

    for pos in request.positions:
        returns = request.instrument_returns.get(pos.instrument_id)
        proxy_used = returns is None
        if proxy_used:
            returns = ASSET_CLASS_PROXY_RETURNS.get(
                pos.asset_class,
                ASSET_CLASS_PROXY_RETURNS[AssetClass.EQUITY],
            )

        daily_pnl = [float(r) * pos.market_value for r in returns]
        total_pnl = sum(daily_pnl)

        impacts.append(
            PositionReplayImpact(
                instrument_id=pos.instrument_id,
                asset_class=pos.asset_class,
                market_value=pos.market_value,
                pnl_impact=total_pnl,
                daily_pnl=daily_pnl,
                proxy_used=proxy_used,
            )
        )

    total_pnl_impact = sum(imp.pnl_impact for imp in impacts)

    return HistoricalReplayRunResult(
        scenario_name=request.scenario_name,
        total_pnl_impact=total_pnl_impact,
        position_impacts=impacts,
        window_start=request.window_start,
        window_end=request.window_end,
    )


def assemble_replay_breach_summary(
    replays: list[HistoricalReplayResult],
    var_value: float,
) -> dict:
    """Count VaR breaches across a collection of simple historical replay results.

    Args:
        replays: List of ``HistoricalReplayResult`` from backtesting.
        var_value: The VaR threshold to compare against.

    Returns:
        Dict with ``total_replays``, ``breach_count``, ``breach_rate``,
        and ``worst_loss``.
    """
    if not replays:
        return {
            "total_replays": 0,
            "breach_count": 0,
            "breach_rate": 0.0,
            "worst_loss": 0.0,
        }

    breach_count = sum(1 for r in replays if r.is_var_breach(var_value))
    worst_loss = max(r.hypothetical_loss() for r in replays)

    return {
        "total_replays": len(replays),
        "breach_count": breach_count,
        "breach_rate": breach_count / len(replays),
        "worst_loss": worst_loss,
    }
