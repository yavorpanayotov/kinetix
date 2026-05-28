"""Yen carry-trade unwind stress scenario.

The classic JPY carry trade borrows in low-yielding yen and invests
in high-yielding USD/AUD/EM assets. A sudden yen rally — funding
shock, BoJ rate-hike surprise, risk-off episode — forces leveraged
holders to close out, which compounds the rally. The August 2024
unwind is the most recent textbook example: USDJPY dropped from
161 to 142 in two weeks while AUD, MXN, EM equities sold off in
parallel.

This scenario applies:
  - USDJPY shock: -10% (yen rallies against dollar)
  - Funding cost shock: +200bps on USD overnight
  - EM equity shock: -8%
  - Risk-on FX shock: AUD/USD, MXN/USD -5%
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class YenCarryUnwindShock:
    """Per-axis shocks for the JPY-carry unwind scenario."""

    usdjpy_pct: float = -0.10
    funding_cost_bps: int = 200
    em_equity_pct: float = -0.08
    risk_on_fx_pct: float = -0.05


def apply_yen_carry_unwind_to_funded_position(
    usd_notional: float,
    leverage: float,
    initial_carry_rate: float,
    shock: YenCarryUnwindShock = YenCarryUnwindShock(),
) -> float:
    """Approximate P&L impact on a JPY-funded leveraged position.

    Two cost drivers compound:
      1) FX revaluation: yen-denominated debt grew in USD terms by
         |usdjpy_pct| × notional × leverage.
      2) Funding shock: extra interest cost from rate hike on the
         leveraged USD leg.

    Reported as a positive loss number.
    """
    fx_loss = abs(shock.usdjpy_pct) * usd_notional * leverage
    funding_loss = (shock.funding_cost_bps / 10_000.0) * usd_notional * leverage
    return fx_loss + funding_loss
