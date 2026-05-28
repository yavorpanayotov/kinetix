"""Straddle and strangle Greeks decomposition.

A straddle is long-call + long-put at the same strike; a strangle is
long-call (high strike) + long-put (low strike). Both are pure-vega
plays — the trader wants the underlying to move but doesn't care
which direction. Decomposing the position's Greeks back to the
constituent legs is a standard pre-trade and risk-monitoring check.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class LegGreeks:
    """Greeks for one leg of a multi-leg position."""

    delta: float
    gamma: float
    vega: float
    theta: float


@dataclass(frozen=True)
class StraddleGreeks:
    """Combined and per-leg Greeks for a straddle or strangle."""

    call_leg: LegGreeks
    put_leg: LegGreeks

    @property
    def delta(self) -> float:
        return self.call_leg.delta + self.put_leg.delta

    @property
    def gamma(self) -> float:
        return self.call_leg.gamma + self.put_leg.gamma

    @property
    def vega(self) -> float:
        return self.call_leg.vega + self.put_leg.vega

    @property
    def theta(self) -> float:
        return self.call_leg.theta + self.put_leg.theta


def combine_straddle_legs(call: LegGreeks, put: LegGreeks) -> StraddleGreeks:
    """Combine the two legs of a straddle/strangle into a single
    GreeksResult-shaped struct. Each Greek is the sum of the legs.
    """
    return StraddleGreeks(call_leg=call, put_leg=put)
