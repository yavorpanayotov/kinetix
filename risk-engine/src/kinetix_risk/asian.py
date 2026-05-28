"""Asian option pricing via Turnbull-Wakeman moment-matching.

An Asian option's payoff is based on the AVERAGE of the underlying
over the option's life rather than its terminal value. Two flavours:
arithmetic average (payoff = max(avg - K, 0)) and geometric average
(payoff = max(geo_avg - K, 0)). Geometric is rare in practice; the
practitioner default is arithmetic, which has no closed-form
Black-Scholes equivalent.

Turnbull-Wakeman (1991) approximates the arithmetic Asian by
matching the first two moments of the arithmetic average to a
log-normal distribution, then pricing the resulting log-normal
underlying with standard Black-Scholes. Accurate to within ~0.5%
of Monte Carlo for typical inputs.

Reference
---------
Turnbull, S. M., & Wakeman, L. M. (1991). A quick algorithm for
pricing European average options. *Journal of Financial and
Quantitative Analysis*, 26(3), 377-389.
"""

import math


def asian_arithmetic_call_turnbull_wakeman(
    spot: float, strike: float, time_to_expiry: float,
    risk_free_rate: float, dividend_yield: float, vol: float,
) -> float:
    """Price an arithmetic-average Asian call via Turnbull-Wakeman."""
    if time_to_expiry <= 0:
        return max(0.0, spot - strike)
    if spot <= 0 or strike <= 0:
        raise ValueError("spot and strike must be positive")
    b = risk_free_rate - dividend_yield  # cost-of-carry
    if abs(b) < 1e-12:
        m1 = spot
        m2 = (2.0 * spot * spot / (vol * vol * time_to_expiry)) * (
            (math.exp(vol * vol * time_to_expiry) - 1.0) / (vol * vol * time_to_expiry) - 1.0
        )
    else:
        m1 = spot * (math.exp(b * time_to_expiry) - 1.0) / (b * time_to_expiry)
        denom = (b + vol * vol) * (2.0 * b + vol * vol)
        m2 = (
            2.0 * spot * spot * math.exp((2.0 * b + vol * vol) * time_to_expiry)
            / denom
            + 2.0 * spot * spot / (b * b) * (
                1.0 / (2.0 * b + vol * vol)
                - math.exp(b * time_to_expiry) / (b + vol * vol)
            )
        ) / (time_to_expiry * time_to_expiry)
    # Implied vol of the average matches the second moment.
    if m1 <= 0:
        return 0.0
    sigma_a_sq = math.log(m2 / (m1 * m1)) / time_to_expiry
    sigma_a = math.sqrt(max(0.0, sigma_a_sq))
    if sigma_a <= 0:
        return max(0.0, math.exp(-risk_free_rate * time_to_expiry) * (m1 - strike))
    d1 = (math.log(m1 / strike) + 0.5 * sigma_a * sigma_a * time_to_expiry) / (sigma_a * math.sqrt(time_to_expiry))
    d2 = d1 - sigma_a * math.sqrt(time_to_expiry)
    from math import erf
    def N(x: float) -> float:
        return 0.5 * (1.0 + erf(x / math.sqrt(2)))
    discount = math.exp(-risk_free_rate * time_to_expiry)
    return discount * (m1 * N(d1) - strike * N(d2))
