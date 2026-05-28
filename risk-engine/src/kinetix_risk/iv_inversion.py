"""Implied-volatility inversion via Newton-Raphson on bs_price.

Market data publishes option prices; pricers need vols. The
implied-vol inverter takes an observed option price and solves
``bs_price(sigma) = price_target`` for sigma. Newton-Raphson with
the analytic vega derivative converges quadratically (typically
5-7 iterations for a sensible starting point).

Convergence is gated on both an absolute price tolerance (default
1e-8) and an iteration cap (default 50). If neither is reached the
function raises — better to fail closed than return a wrong vol
that propagates into Greeks.
"""

import math


def implied_vol(
    market_price: float,
    spot: float,
    strike: float,
    time_to_expiry_years: float,
    risk_free_rate: float,
    dividend_yield: float = 0.0,
    option_kind: str = "call",
    initial_vol: float = 0.20,
    tolerance: float = 1e-8,
    max_iterations: int = 50,
) -> float:
    """Solve for the implied volatility that prices the option at
    [market_price] under Black-Scholes.

    @raise ValueError: invalid inputs or non-convergence.
    """
    if market_price <= 0:
        raise ValueError(f"market_price must be positive (got {market_price})")
    if time_to_expiry_years <= 0:
        raise ValueError(f"time_to_expiry_years must be positive")
    if option_kind not in ("call", "put"):
        raise ValueError(f"option_kind must be 'call' or 'put' (got {option_kind})")

    sigma = initial_vol
    for _ in range(max_iterations):
        price = _bs_price_inline(spot, strike, time_to_expiry_years, risk_free_rate, sigma, dividend_yield, option_kind)
        diff = price - market_price
        if abs(diff) < tolerance:
            return sigma
        vega = _bs_vega_inline(spot, strike, time_to_expiry_years, risk_free_rate, sigma, dividend_yield)
        if vega < 1e-12:
            raise ValueError("implied_vol: vega is zero — option insensitive to vol")
        sigma -= diff / vega
        if sigma <= 0:
            sigma = 1e-6  # snap back into the valid range if Newton overshoots
    raise ValueError(
        f"implied_vol: failed to converge within {max_iterations} iterations",
    )


def _bs_price_inline(s: float, k: float, t: float, r: float, vol: float, q: float, kind: str) -> float:
    from math import erf, log, sqrt, exp
    if vol <= 0:
        intrinsic = max(0.0, (s - k) if kind == "call" else (k - s))
        return intrinsic
    d1 = (log(s / k) + (r - q + 0.5 * vol * vol) * t) / (vol * sqrt(t))
    d2 = d1 - vol * sqrt(t)
    def N(x: float) -> float:
        return 0.5 * (1.0 + erf(x / sqrt(2)))
    if kind == "call":
        return s * exp(-q * t) * N(d1) - k * exp(-r * t) * N(d2)
    return k * exp(-r * t) * N(-d2) - s * exp(-q * t) * N(-d1)


def _bs_vega_inline(s: float, k: float, t: float, r: float, vol: float, q: float) -> float:
    from math import log, sqrt, exp, pi
    d1 = (log(s / k) + (r - q + 0.5 * vol * vol) * t) / (vol * sqrt(t))
    # Standard-normal PDF at d1.
    pdf_d1 = (1.0 / sqrt(2.0 * pi)) * exp(-0.5 * d1 * d1)
    return s * exp(-q * t) * pdf_d1 * sqrt(t)
