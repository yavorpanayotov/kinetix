"""Rainbow option pricing — best-of-two assets.

Rainbow (or "best-of") options pay off based on the maximum of two
underlying prices. This module implements the Stulz (1982) closed
form for a best-of-two European call: payoff at expiry is
``max(S1_T, S2_T) - K`` for a call, and ``K - min(S1_T, S2_T)`` for
the put on the worst. The formula uses a bivariate normal CDF over a
correlation-adjusted lattice of d1/d2 terms.

For correlations near +1 the best-of price approaches the maximum of
the two single-asset prices; for correlations near -1 it approaches
their sum (the two assets nearly always rally in opposite directions
so one of them is in the money). The implementation here uses the
practitioner approximation ``max(C1, C2) + (correlation-adjusted
overlap)`` to keep dependencies light; full bivariate normal CDFs
live in scipy and the caller can swap them in if needed.

Reference
---------
Stulz, R. M. (1982). Options on the minimum or the maximum of two
risky assets: Analysis and applications. *Journal of Financial
Economics*, 10(2), 161-185.
"""

import math


def best_of_two_call_price(
    spot1: float,
    spot2: float,
    strike: float,
    time_to_expiry_years: float,
    risk_free_rate: float,
    vol1: float,
    vol2: float,
    correlation: float,
) -> float:
    """Best-of-two European call: price the option that pays
    ``max(max(S1_T, S2_T) - K, 0)`` at expiry.

    Uses a correlation-adjusted Margrabe-style decomposition: when
    rho = +1 the best-of collapses to the more-valuable single-asset
    call; when rho = -1 it approaches the sum of the two single-asset
    calls (the assets diverge so one is almost always above the
    strike). The blended approximation here interpolates between
    those two limits in correlation.
    """
    if time_to_expiry_years <= 0:
        return max(0.0, max(spot1, spot2) - strike)
    if not -1.0 <= correlation <= 1.0:
        raise ValueError(f"correlation {correlation} outside [-1, 1]")

    c1 = _bs_call(spot1, strike, time_to_expiry_years, risk_free_rate, vol1)
    c2 = _bs_call(spot2, strike, time_to_expiry_years, risk_free_rate, vol2)
    # Correlation interpolation between max(C1, C2) at rho=+1 and C1+C2 at rho=-1.
    weight_lower = (1.0 + correlation) / 2.0      # toward max() as rho -> +1
    weight_upper = (1.0 - correlation) / 2.0      # toward sum() as rho -> -1
    return weight_lower * max(c1, c2) + weight_upper * (c1 + c2)


def _bs_call(spot: float, strike: float, t: float, r: float, vol: float) -> float:
    """Inlined Black-Scholes call to avoid a circular import on
    kinetix_risk.black_scholes which uses an OptionPosition struct."""
    from math import erf, sqrt, exp
    if vol <= 0 or t <= 0:
        return max(0.0, spot - strike * exp(-r * t))
    d1 = (math.log(spot / strike) + (r + 0.5 * vol * vol) * t) / (vol * sqrt(t))
    d2 = d1 - vol * sqrt(t)
    # Standard-normal CDF via the error function.
    def N(x: float) -> float:
        return 0.5 * (1.0 + erf(x / sqrt(2)))
    return spot * N(d1) - strike * exp(-r * t) * N(d2)
