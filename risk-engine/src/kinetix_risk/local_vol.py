"""Dupire local volatility extracted from the implied-vol smile.

Black-Scholes assumes a single constant vol; the real implied-vol
surface varies across strike (the smile/skew) and time. Dupire (1994)
showed how to back out a "local" volatility function ``\\sigma(S, t)``
that, when fed into the diffusion ``dS = r S dt + \\sigma(S,t) S dW``,
reproduces the entire observed European-option price surface.

The simplest discrete-grid Dupire formula in implied-vol form is

.. math::

    \\sigma_{loc}^2(K, T) = \\frac{\\sigma^2 + 2\\sigma T \\partial_T\\sigma}{\\left(1 - \\frac{K \\partial_K\\sigma}{\\sigma\\sqrt{T}} d_1\\right)^2 + K^2 T \\sigma (\\partial_{KK}\\sigma - d_1 \\sqrt{T} (\\partial_K\\sigma)^2)}

This module implements the *finite-difference* form on a 2D grid of
implied vols, so callers can pass their fitted surface in and get a
matching local-vol grid out.

References
----------
Dupire, B. (1994). Pricing with a smile. *Risk Magazine*, 7(1), 18-20.
"""

import math


def local_vol_from_smile(
    implied_vol_grid: list[list[float]],
    strikes: list[float],
    expiries_years: list[float],
    spot: float,
    risk_free_rate: float = 0.0,
    dividend_yield: float = 0.0,
) -> list[list[float]]:
    """Compute Dupire local-vol grid from an implied-vol grid.

    Inputs:
      - implied_vol_grid[i][j] = sigma at strike i, expiry j
      - strikes[i] in ascending order
      - expiries_years[j] in ascending order

    Returns a grid of the same shape with local vol values. At the
    boundaries (first/last strike or expiry), the local vol equals
    the implied vol — the finite-difference derivatives aren't
    computable there. This is the standard practitioner workaround.
    """
    n_k = len(strikes)
    n_t = len(expiries_years)
    if n_k < 3 or n_t < 2:
        # Not enough grid points for the finite difference; return
        # implied vol as a no-op fallback.
        return [row[:] for row in implied_vol_grid]
    out = [row[:] for row in implied_vol_grid]
    for i in range(1, n_k - 1):
        for j in range(1, n_t):
            sigma = implied_vol_grid[i][j]
            T = expiries_years[j]
            K = strikes[i]
            # dSigma/dT (one-sided backward difference)
            dsigma_dT = (implied_vol_grid[i][j] - implied_vol_grid[i][j - 1]) / (T - expiries_years[j - 1])
            # dSigma/dK (central difference)
            dsigma_dK = (implied_vol_grid[i + 1][j] - implied_vol_grid[i - 1][j]) / (strikes[i + 1] - strikes[i - 1])
            # d2Sigma/dK2 (central second difference)
            d2sigma_dK2 = (
                implied_vol_grid[i + 1][j]
                - 2 * implied_vol_grid[i][j]
                + implied_vol_grid[i - 1][j]
            ) / ((strikes[i + 1] - strikes[i]) * (strikes[i] - strikes[i - 1]))
            if sigma <= 0 or T <= 0:
                out[i][j] = sigma
                continue
            d1 = (math.log(spot / K) + (risk_free_rate - dividend_yield + 0.5 * sigma * sigma) * T) / (sigma * math.sqrt(T))
            numerator = sigma * sigma + 2 * sigma * T * dsigma_dT
            # Simplified Dupire (skew-and-smile only) — denominator uses dsigma_dK and d2sigma_dK2.
            term1 = (1.0 - K * dsigma_dK / (sigma * math.sqrt(T)) * d1) ** 2
            term2 = K * K * T * sigma * (d2sigma_dK2 - d1 * math.sqrt(T) * dsigma_dK * dsigma_dK)
            denom = term1 + term2
            if denom <= 0:
                out[i][j] = sigma  # fall back to implied vol if numerics break
                continue
            local_var = numerator / denom
            out[i][j] = math.sqrt(max(0.0, local_var))
    return out
