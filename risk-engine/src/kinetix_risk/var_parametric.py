"""Parametric (variance-covariance) Value at Risk.

Derivation
----------
Let ``r_t`` be the per-day log-return of a position, and assume

.. math::

    r_t \\sim \\mathcal{N}(\\mu, \\sigma^2), \\quad
    \\text{Cov}(r_s, r_t) = 0 \\;\\; \\forall s \\neq t.

That is, daily returns are *independent and identically distributed*
(i.i.d.). Under i.i.d., the T-day return is the sum
``R_T = r_1 + r_2 + ... + r_T`` of T independent normals, so

.. math::

    R_T \\sim \\mathcal{N}(T \\mu, T \\sigma^2),

and the standard deviation grows as ``\\sigma \\sqrt{T}`` — this is
the *square-root-of-time* scaling. For small drift over short
horizons we approximate ``\\mu \\approx 0`` and get the practitioner
formula

.. math::

    VaR_\\alpha(T) = \\Phi^{-1}(\\alpha) \\, \\sigma \\, \\sqrt{T} \\, V_0,

where ``\\alpha`` is the confidence level (typically 0.99 or 0.999),
``\\Phi^{-1}`` is the standard-normal quantile, ``\\sigma`` is the
1-day return standard deviation, and ``V_0`` is current notional.
For multi-asset portfolios, replace ``\\sigma`` with
``\\sqrt{w^T \\Sigma w}`` where ``\\Sigma`` is the daily-return
covariance matrix and ``w`` the asset-weight vector.

Equivalently:

.. math::

    VaR(T) = VaR(1) \\cdot \\sqrt{T}

which is the Basel pillar-1 multi-day scaling rule.

Validity & limitations
----------------------
The i.i.d. assumption holds reasonably well for short horizons
(1-10 days) in liquid markets. It understates risk when:

  - returns exhibit autocorrelation — positive serial correlation
    makes multi-day losses larger than ``\\sqrt{T}`` scaling implies;
  - volatility is time-varying / clustered — GARCH effects mean
    variance does not grow linearly with time;
  - return distributions have fat tails or skew — the normality
    assumption breaks down and ``\\Phi^{-1}`` understates the tail.

Cornish-Fisher and EVT-based VaR variants relax these assumptions
at the cost of additional model complexity.
"""

import numpy as np
from scipy.stats import norm

from kinetix_risk.models import AssetClassExposure, ConfidenceLevel, ComponentBreakdown, VaRResult

TRADING_DAYS_PER_YEAR = 252


def calculate_parametric_var(
    exposures: list[AssetClassExposure],
    confidence_level: ConfidenceLevel,
    time_horizon_days: int,
    correlation_matrix: np.ndarray,
) -> VaRResult:
    n = len(exposures)
    market_values = np.array([e.total_market_value for e in exposures])
    daily_vols = np.array([e.volatility / np.sqrt(TRADING_DAYS_PER_YEAR) for e in exposures])

    # Dollar-weighted volatilities
    dollar_vols = daily_vols * market_values

    # Covariance matrix in dollar terms
    cov_matrix = np.outer(dollar_vols, dollar_vols) * correlation_matrix

    # Portfolio standard deviation
    ones = np.ones(n)
    port_variance = float(ones @ cov_matrix @ ones)
    port_std = np.sqrt(port_variance)

    alpha = confidence_level.value
    z = norm.ppf(alpha)
    # Basel sqrt(T) rule: scales 1-day VaR to T-day VaR under the i.i.d. normally
    # distributed returns assumption.  Valid for short horizons (1-10 days);
    # understates risk under autocorrelation, volatility clustering, or fat tails.
    sqrt_t = np.sqrt(time_horizon_days)

    var_value = z * port_std * sqrt_t

    # Analytical ES for normal distribution: ES = sigma * phi(z) / (1 - alpha)
    es_value = port_std * norm.pdf(z) / (1 - alpha) * sqrt_t

    # Component VaR via Euler allocation: CVaR_i = (Cov @ 1)_i * z / port_std
    marginal = cov_matrix @ ones
    if port_std == 0:
        component_var = np.zeros(n)
    else:
        component_var = marginal * z / port_std * sqrt_t

    breakdown = []
    for i, exp in enumerate(exposures):
        pct = (component_var[i] / var_value * 100) if var_value > 0 else 0.0
        breakdown.append(ComponentBreakdown(exp.asset_class, float(component_var[i]), float(pct)))

    return VaRResult(float(var_value), float(es_value), breakdown)
