"""SVB 2023 — rates shock + duration risk scenario.

Silicon Valley Bank collapsed in March 2023 after a Fed tightening
cycle had pushed the value of its long-duration HTM Treasury and
MBS book deep underwater (unrealised losses ~$17bn against ~$15bn
of regulatory capital). A run on uninsured deposits forced asset
sales at the realised loss, and the bank was placed into FDIC
receivership over a single weekend. The scenario captures the two
drivers: a sharp rates move and a heavy-duration HTM portfolio.

Shocks:
  - Rates shock: +200bps parallel
  - Duration risk: long-duration HTM book takes the brunt
  - Deposit-flight haircut: 40% of uninsured deposits leave
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Svb2023Shock:
    rates_shock_bps: int = 200
    htm_duration_years: float = 6.0
    deposit_flight_pct: float = 0.40


def apply_svb_2023_to_htm_book(
    htm_market_value: float,
    duration_years: float,
    shock: Svb2023Shock = Svb2023Shock(),
) -> float:
    """Loss on a held-to-maturity bond book under the SVB rates shock.

    P&L from a parallel rate move on a duration-D book:
    loss ~= MV * D * (rate_shock).
    Reported as a positive loss number.
    """
    rate_move = shock.rates_shock_bps / 10_000.0
    return htm_market_value * duration_years * rate_move
