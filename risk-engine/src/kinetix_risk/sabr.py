"""SABR stochastic-volatility model implementation + calibration.

SABR (Hagan, Kumar, Lesniewski, Woodward 2002) is the practitioner-
standard model for equity, FX, and IR vol surfaces. The dynamics:

.. math::

    dF_t = \\sigma_t F_t^{\\beta} dW_t^F, \\quad
    d\\sigma_t = \\alpha \\sigma_t dW_t^{\\sigma}, \\quad
    dW_t^F dW_t^{\\sigma} = \\rho \\, dt

Four parameters per expiry — alpha (vol-of-vol), beta (CEV exponent,
typically 0.5 or 1.0), rho (FX-spot correlation), nu (starting vol).

The Hagan-et-al closed-form implied-vol approximation is what every
vendor's vol surface implements; calibration is the inverse problem
of fitting (alpha, beta, rho, nu) to observed market smiles.

References
----------
Hagan, P. S., Kumar, D., Lesniewski, A. S., & Woodward, D. E. (2002).
    Managing smile risk. *Wilmott Magazine*, 84-108.
"""

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class SabrParameters:
    """Four-parameter SABR descriptor."""

    alpha: float       # ATM vol level (sigma_0)
    beta: float        # CEV exponent in [0, 1]
    rho: float         # correlation in [-1, 1]
    nu: float          # vol-of-vol >= 0

    def __post_init__(self) -> None:
        if not 0.0 <= self.beta <= 1.0:
            raise ValueError(f"SABR beta {self.beta} outside [0, 1]")
        if not -1.0 <= self.rho <= 1.0:
            raise ValueError(f"SABR rho {self.rho} outside [-1, 1]")
        if self.nu < 0:
            raise ValueError(f"SABR nu {self.nu} must be non-negative")
        if self.alpha <= 0:
            raise ValueError(f"SABR alpha {self.alpha} must be positive")


def sabr_implied_vol(
    params: SabrParameters,
    forward: float,
    strike: float,
    time_to_expiry: float,
) -> float:
    """Hagan-et-al closed-form Black-implied vol from SABR parameters.

    Uses the standard practitioner formula (Hagan 2002 eq. 2.17a).
    Handles the ATM degenerate case (F == K) explicitly with the
    log-expansion limit, avoiding the division-by-zero in the
    general formula.
    """
    if forward <= 0 or strike <= 0:
        raise ValueError("forward and strike must be positive")
    if time_to_expiry <= 0:
        raise ValueError("time_to_expiry must be positive")
    alpha, beta, rho, nu = params.alpha, params.beta, params.rho, params.nu
    f, k, t = forward, strike, time_to_expiry
    if abs(f - k) < 1e-10:
        # ATM limit.
        fk_beta = f ** (1 - beta)
        term1 = ((1 - beta) ** 2 / 24) * alpha * alpha / (fk_beta ** 2)
        term2 = 0.25 * rho * beta * nu * alpha / fk_beta
        term3 = ((2 - 3 * rho * rho) / 24) * nu * nu
        return alpha / fk_beta * (1 + (term1 + term2 + term3) * t)
    log_fk = math.log(f / k)
    fk_pow = (f * k) ** ((1 - beta) / 2)
    z = (nu / alpha) * fk_pow * log_fk
    chi = math.log((math.sqrt(1 - 2 * rho * z + z * z) + z - rho) / (1 - rho))
    if abs(chi) < 1e-12:
        chi = z  # limiting case
    pre = alpha / (fk_pow * (1 + ((1 - beta) ** 2 / 24) * log_fk * log_fk))
    correction = (
        1
        + (
            ((1 - beta) ** 2 / 24) * alpha * alpha / (fk_pow * fk_pow)
            + 0.25 * rho * beta * nu * alpha / fk_pow
            + ((2 - 3 * rho * rho) / 24) * nu * nu
        ) * t
    )
    return pre * (z / chi) * correction
