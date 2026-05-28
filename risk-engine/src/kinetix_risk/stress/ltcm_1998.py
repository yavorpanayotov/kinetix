"""LTCM 1998 — liquidity crunch + correlation spike scenario.

In August-September 1998, the Russian debt default and ruble
devaluation triggered a global flight to quality. Long-Term Capital
Management held large convergent-trade positions (long off-the-run
US Treasuries vs short on-the-run, long EM debt vs short USTs,
short equity vol) that all moved against them simultaneously when
correlations spiked. The fund lost $4.6bn and was rescued by a
consortium of major dealers in late September.

The scenario applies:
  - Credit-spread shock: +200bps on IG, +500bps on HY
  - Equity-vol shock: VIX +50%
  - Cross-asset correlation spike: pairwise correlations -> 0.85
  - Liquidity haircut: 30% on illiquid positions
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Ltcm1998Shock:
    ig_spread_bps: int = 200
    hy_spread_bps: int = 500
    equity_vol_shock_pct: float = 0.50
    correlation_floor: float = 0.85
    illiquid_haircut_pct: float = 0.30


def apply_ltcm_1998_to_convergent_position(
    long_leg_notional: float,
    short_leg_notional: float,
    spread_shock_bps: int,
    illiquidity_score: float,
    shock: Ltcm1998Shock = Ltcm1998Shock(),
) -> float:
    """P&L impact on a long-short convergent trade.

    Convergent trades go long the cheap leg and short the rich leg
    expecting the spread to narrow. An LTCM-style stress widens the
    spread instead — both legs move against the position. Plus the
    illiquidity haircut applies to whatever fraction of the book
    can't be unwound at quoted prices.

    Returns the loss as a positive number.
    """
    spread_widening = spread_shock_bps / 10_000.0
    # Both legs lose: the long leg's spread widens (price falls); the
    # short leg also widens but it's a short. Net: roughly the
    # spread-shock times the absolute notional difference.
    spread_loss = abs(long_leg_notional - short_leg_notional) * spread_widening
    # Liquidity haircut applies to the LONG leg only (the short leg
    # is a hedge, not a liquidation target).
    liquidity_loss = long_leg_notional * illiquidity_score * shock.illiquid_haircut_pct
    return spread_loss + liquidity_loss
