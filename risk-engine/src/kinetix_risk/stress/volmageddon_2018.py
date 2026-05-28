"""Volmageddon 2018 — short vol unwind scenario.

On 5 February 2018 the VIX nearly doubled in a single session (15.0
-> 37.3 close, +116% in one day). The XIV inverse-VIX ETN lost
~93% intraday and was unwound the following day. Holders of short
volatility positions (single stocks, ETFs, structured products)
faced concurrent margin calls and forced unwinds — a feedback loop
that amplified the move.

This module returns the stress overlays a Volmageddon-style scenario
applies to a portfolio:

  - vol_shock_multiplier: implied vols multiply by ~2.5x
  - equity_spot_shock: SPX drops ~4% on day-1
  - vix_basis_shock: VIX futures basis steepens severely

References
----------
Federal Reserve Bank of St. Louis. *The February 2018 Spike in
    Equity Market Volatility*. Economic Synopses No. 7 (2018).
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Volmageddon2018Shock:
    """The shocks of the Volmageddon-2018 stress scenario."""

    vol_shock_multiplier: float = 2.5
    equity_spot_shock_pct: float = -0.04
    vix_basis_shock_pct: float = 0.50


def apply_volmageddon_2018_to_short_vol_position(
    notional: float,
    initial_vol: float,
    shock: Volmageddon2018Shock = Volmageddon2018Shock(),
) -> float:
    """Approximate the P&L impact on a SHORT volatility position.

    A short-vol position loses money when realised/implied vol spikes;
    the simplified P&L is ``-notional * (new_vol - initial_vol)``.
    For the Volmageddon scenario, ``new_vol = initial_vol * 2.5`` so
    the loss is ``-notional * 1.5 * initial_vol`` (returned as a
    positive loss number).
    """
    new_vol = initial_vol * shock.vol_shock_multiplier
    pnl = -notional * (new_vol - initial_vol)
    return -pnl  # report as positive loss
