"""Spread option pricing via Kirk's approximation.

A spread option pays ``max(S1 - S2 - K, 0)`` at expiry — a bet on
the *difference* between two underlyings. Common in energy
(WTI-Brent), rates (5y10y curve), credit (single-name vs index),
and FX (cross-currency). The exact pricer requires a 2D Monte
Carlo; Kirk (1995) gives a fast closed-form approximation that
treats the spread as a single underlying with adjusted vol.

.. math::

    \\sigma_{spread}^2 = \\sigma_1^2 - 2 \\rho \\sigma_1 \\sigma_2 \\frac{S_2}{S_2 + K} + \\left(\\sigma_2 \\frac{S_2}{S_2 + K}\\right)^2

References
----------
Kirk, E. (1995). Correlation in the energy markets. *Managing
Energy Price Risk*, 71-78.
"""

import math


def kirk_spread_call_price(
    spot1: float,
    spot2: float,
    strike: float,
    time_to_expiry_years: float,
    risk_free_rate: float,
    vol1: float,
    vol2: float,
    correlation: float,
) -> float:
    """Spread call: payoff max(S1 - S2 - K, 0).

    Returns Kirk's-approximation price. Convergence to the exact
    Monte-Carlo answer is within ~1% for typical FX/energy spreads;
    breaks down when the spread approaches zero (Kirk assumes
    ``S2 + K`` is positive).
    """
    if time_to_expiry_years <= 0:
        return max(0.0, spot1 - spot2 - strike)
    if not -1.0 <= correlation <= 1.0:
        raise ValueError(f"correlation {correlation} outside [-1, 1]")
    if spot2 + strike <= 0:
        raise ValueError("Kirk's formula requires spot2 + strike > 0")
    s2_factor = spot2 / (spot2 + strike)
    vol_eff_sq = (
        vol1 * vol1
        - 2 * correlation * vol1 * vol2 * s2_factor
        + vol2 * vol2 * s2_factor * s2_factor
    )
    vol_eff = math.sqrt(max(0.0, vol_eff_sq))
    # Treat (S1 / (S2 + K)) as the "effective underlying" against strike 1.
    if vol_eff <= 0 or time_to_expiry_years <= 0:
        return max(0.0, (spot1 - (spot2 + strike) * math.exp(-risk_free_rate * time_to_expiry_years)))
    f = spot1 / (spot2 + strike)
    d1 = (math.log(f) + 0.5 * vol_eff * vol_eff * time_to_expiry_years) / (
        vol_eff * math.sqrt(time_to_expiry_years)
    )
    d2 = d1 - vol_eff * math.sqrt(time_to_expiry_years)
    from math import erf
    def N(x: float) -> float:
        return 0.5 * (1.0 + erf(x / math.sqrt(2)))
    discount = math.exp(-risk_free_rate * time_to_expiry_years)
    return (spot2 + strike) * (f * N(d1) - N(d2)) * discount
