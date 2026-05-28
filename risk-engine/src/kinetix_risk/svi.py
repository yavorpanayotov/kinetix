"""Stochastic Volatility Inspired (SVI) total-variance parameterisation.

SVI (Gatheral 2004) parameterises the total implied variance ``w(k)``
as a function of log-moneyness ``k = log(K/F)`` using five
parameters: ``a``, ``b``, ``rho``, ``m``, ``sigma``.

.. math::

    w(k) = a + b \\left(\\rho (k - m) + \\sqrt{(k - m)^2 + \\sigma^2}\\right)

Each parameter has a clean financial interpretation:
  - a = vertical-shift (level of variance at log-moneyness m)
  - b = right-end asymptotic slope (controls smile width)
  - rho = correlation, in [-1, 1] — sets the skew direction
  - m = horizontal-shift (centres the smile)
  - sigma = curvature smoothness near the vertex

The SVI form is preferred over polynomial fits because it is
*static* — i.e. arbitrage-free at the no-butterfly and no-calendar
conditions for natural parameter ranges. Calibration to market
smiles is via least squares on observed total-variance values.

Reference
---------
Gatheral, J. (2004). A parsimonious arbitrage-free implied volatility
parameterization with application to the valuation of volatility
derivatives. *Bloomberg Quant Group Slides*.
"""

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class SviParameters:
    """Five-parameter SVI total-variance descriptor."""

    a: float
    b: float
    rho: float
    m: float
    sigma: float

    def __post_init__(self) -> None:
        if self.b < 0:
            raise ValueError(f"SVI b must be non-negative (got {self.b})")
        if not -1.0 <= self.rho <= 1.0:
            raise ValueError(f"SVI rho {self.rho} outside [-1, 1]")
        if self.sigma <= 0:
            raise ValueError(f"SVI sigma must be positive (got {self.sigma})")


def svi_total_variance(params: SviParameters, log_moneyness: float) -> float:
    """Evaluate the SVI parameterisation at log-moneyness k."""
    a, b, rho, m, sigma = params.a, params.b, params.rho, params.m, params.sigma
    shifted = log_moneyness - m
    return a + b * (rho * shifted + math.sqrt(shifted * shifted + sigma * sigma))


def svi_implied_vol(
    params: SviParameters,
    log_moneyness: float,
    time_to_expiry: float,
) -> float:
    """Convert SVI total variance to implied volatility."""
    if time_to_expiry <= 0:
        raise ValueError("time_to_expiry must be positive")
    total_var = svi_total_variance(params, log_moneyness)
    if total_var <= 0:
        return 0.0
    return math.sqrt(total_var / time_to_expiry)
