"""Digital (binary) option pricing.

A *cash-or-nothing* digital pays a fixed cash amount if the option
finishes in the money, zero otherwise. An *asset-or-nothing* digital
pays the underlying asset value (S) if in the money, zero otherwise.

Both are building blocks of structured payoffs: a vanilla call is
``S * asset_or_nothing_call - K * cash_or_nothing_call`` (the
asset-pay branch minus the strike-cost branch).

Reference
---------
Hull, J. C. (2018). *Options, Futures, and Other Derivatives* (10th ed.).
    Chapter 26 (Exotic Options).
"""

import math


def _N(x: float) -> float:
    from math import erf, sqrt
    return 0.5 * (1.0 + erf(x / sqrt(2)))


def _d2(spot: float, strike: float, t: float, r: float, q: float, sigma: float) -> float:
    return (math.log(spot / strike) + (r - q - 0.5 * sigma * sigma) * t) / (sigma * math.sqrt(t))


def cash_or_nothing_call(
    spot: float, strike: float, time_to_expiry: float,
    risk_free_rate: float, dividend_yield: float, vol: float,
    cash_payout: float = 1.0,
) -> float:
    """Cash-or-nothing call: pays [cash_payout] if S_T > K, else 0."""
    if time_to_expiry <= 0:
        return cash_payout if spot > strike else 0.0
    d2 = _d2(spot, strike, time_to_expiry, risk_free_rate, dividend_yield, vol)
    return cash_payout * math.exp(-risk_free_rate * time_to_expiry) * _N(d2)


def asset_or_nothing_call(
    spot: float, strike: float, time_to_expiry: float,
    risk_free_rate: float, dividend_yield: float, vol: float,
) -> float:
    """Asset-or-nothing call: pays S_T if S_T > K, else 0."""
    if time_to_expiry <= 0:
        return spot if spot > strike else 0.0
    d1 = _d2(spot, strike, time_to_expiry, risk_free_rate, dividend_yield, vol) + vol * math.sqrt(time_to_expiry)
    return spot * math.exp(-dividend_yield * time_to_expiry) * _N(d1)
