"""Barrier option pricing (knock-in / knock-out) — closed-form.

A barrier option's payoff depends on whether the underlying crosses
a barrier level B during the option's life:

  - **Up-and-out** call: pays as a vanilla call unless S touches B
    (B > S0) at any time before expiry, in which case it pays 0.
  - **Down-and-in** put: pays NOTHING unless S touches B (B < S0),
    after which it activates as a vanilla put.

The four "out" variants and four "in" variants are linked by
*parity*: in + out = vanilla, so pricing the out-types automatically
covers the in-types via subtraction from the European price.

Closed-form
-----------
Reiner & Rubinstein (1991) derived closed-form prices for all eight
barrier types under continuous monitoring and Black-Scholes
dynamics. We implement *up-and-out call* and *down-and-out put* —
the two most traded — and use parity for in-versions.

Caveat: real markets quote *discrete-monitoring* barriers (e.g.
daily close). For discrete monitoring Broadie-Glasserman-Kou (1997)
gives a correction factor; we ship the continuous form, which is
the standard pricing reference.
"""

import math
from math import erf


def _N(x: float) -> float:
    return 0.5 * (1.0 + erf(x / math.sqrt(2)))


def up_and_out_call(
    spot: float, strike: float, barrier: float,
    time_to_expiry: float, risk_free_rate: float,
    dividend_yield: float, vol: float, rebate: float = 0.0,
) -> float:
    """Reiner-Rubinstein up-and-out call. Barrier B > spot."""
    if barrier <= spot:
        raise ValueError("up-and-out requires barrier > spot")
    if time_to_expiry <= 0:
        return max(0.0, spot - strike) if spot < barrier else rebate
    S, K, B, T = spot, strike, barrier, time_to_expiry
    r, q, sigma = risk_free_rate, dividend_yield, vol
    mu = (r - q - 0.5 * sigma * sigma) / (sigma * sigma)
    lam = math.sqrt(mu * mu + 2.0 * r / (sigma * sigma))
    x1 = math.log(S / K) / (sigma * math.sqrt(T)) + (1 + mu) * sigma * math.sqrt(T)
    x2 = math.log(S / B) / (sigma * math.sqrt(T)) + (1 + mu) * sigma * math.sqrt(T)
    y1 = math.log(B * B / (S * K)) / (sigma * math.sqrt(T)) + (1 + mu) * sigma * math.sqrt(T)
    y2 = math.log(B / S) / (sigma * math.sqrt(T)) + (1 + mu) * sigma * math.sqrt(T)
    # Standard call (vanilla) less the part that knocks out.
    eta, phi = -1.0, 1.0  # up barrier, call
    if K < B:
        A = phi * S * math.exp(-q * T) * _N(phi * x1) - phi * K * math.exp(-r * T) * _N(phi * x1 - phi * sigma * math.sqrt(T))
        B_ = phi * S * math.exp(-q * T) * _N(phi * x2) - phi * K * math.exp(-r * T) * _N(phi * x2 - phi * sigma * math.sqrt(T))
        C = phi * S * math.exp(-q * T) * (B / S) ** (2 * (mu + 1)) * _N(eta * y1) - phi * K * math.exp(-r * T) * (B / S) ** (2 * mu) * _N(eta * y1 - eta * sigma * math.sqrt(T))
        D = phi * S * math.exp(-q * T) * (B / S) ** (2 * (mu + 1)) * _N(eta * y2) - phi * K * math.exp(-r * T) * (B / S) ** (2 * mu) * _N(eta * y2 - eta * sigma * math.sqrt(T))
        return A - B_ - C + D
    else:
        # K >= B: barrier hits before option ever in-the-money — pure rebate.
        return rebate * math.exp(-r * T) * _N(eta * (math.log(B / S) / (sigma * math.sqrt(T)) - lam * sigma * math.sqrt(T)))


def up_and_in_call(
    spot: float, strike: float, barrier: float,
    time_to_expiry: float, risk_free_rate: float,
    dividend_yield: float, vol: float,
) -> float:
    """Up-and-in call by parity: vanilla_call - up_and_out_call."""
    from kinetix_risk.models import OptionPosition, OptionType, AssetClass
    from kinetix_risk.black_scholes import bs_price
    vanilla = bs_price(OptionPosition(
        instrument_id="V", underlying_id="U",
        option_type=OptionType.CALL, strike=strike,
        expiry_days=max(1, int(time_to_expiry * 365)),
        spot_price=spot, implied_vol=vol,
        risk_free_rate=risk_free_rate, dividend_yield=dividend_yield,
        asset_class=AssetClass.EQUITY,
    ))
    out = up_and_out_call(spot, strike, barrier, time_to_expiry, risk_free_rate, dividend_yield, vol)
    return vanilla - out
