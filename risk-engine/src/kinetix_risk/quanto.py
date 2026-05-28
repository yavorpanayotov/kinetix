"""Quanto option pricing.

A quanto option is written on a foreign-currency underlying but
pays out in the domestic currency at a fixed FX rate (typically 1.0).
The payoff is ``max(S^F - K^F, 0)`` paid in *domestic* currency,
where ``S^F`` and ``K^F`` are quoted in the foreign currency.

The pricing adjustment vs a vanilla foreign call is the "quanto
drift": the foreign asset's risk-neutral drift under the domestic
measure shifts by ``-rho * sigma_S * sigma_FX``, where rho is the
correlation between the foreign asset and the FX rate. A negative
correlation (asset rallies when foreign currency strengthens)
raises the effective drift and the quanto call is worth more than
the vanilla; positive correlation lowers it.

Reference
---------
Hull, J. C. (2018). *Options, Futures, and Other Derivatives* (10th ed.).
    Chapter 27 (Convexity, Timing, and Quanto Adjustments).
"""

import math


def quanto_call_price(
    spot_foreign: float,
    strike_foreign: float,
    time_to_expiry_years: float,
    risk_free_rate_domestic: float,
    risk_free_rate_foreign: float,
    dividend_yield: float,
    vol_asset: float,
    vol_fx: float,
    correlation_asset_fx: float,
) -> float:
    """Price a quanto European call with payoff max(S^F - K^F, 0) paid
    in domestic currency at the fixed FX = 1.0.

    Uses the standard Garman-Kohlhagen-style closed form with the
    quanto-drift adjustment applied to the asset's risk-neutral drift.
    """
    if time_to_expiry_years <= 0:
        return max(0.0, spot_foreign - strike_foreign)
    if spot_foreign <= 0 or strike_foreign <= 0:
        raise ValueError("spot and strike must be positive")
    if vol_asset <= 0 or vol_fx < 0:
        raise ValueError("vols must be non-negative and asset vol > 0")
    if not -1.0 <= correlation_asset_fx <= 1.0:
        raise ValueError(f"correlation {correlation_asset_fx} outside [-1, 1]")

    s = spot_foreign
    k = strike_foreign
    t = time_to_expiry_years
    r_d = risk_free_rate_domestic
    r_f = risk_free_rate_foreign
    q = dividend_yield
    sigma = vol_asset
    quanto_drift = -correlation_asset_fx * sigma * vol_fx
    # The asset's drift under the domestic measure becomes
    # r_f - q + quanto_drift, then discount at r_d.
    drift = r_f - q + quanto_drift
    d1 = (math.log(s / k) + (drift + 0.5 * sigma * sigma) * t) / (sigma * math.sqrt(t))
    d2 = d1 - sigma * math.sqrt(t)
    from math import erf
    def N(x: float) -> float:
        return 0.5 * (1.0 + erf(x / math.sqrt(2)))
    discount = math.exp(-r_d * t)
    return discount * (s * math.exp(drift * t) * N(d1) - k * N(d2))
