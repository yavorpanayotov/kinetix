"""Nelson-Siegel-Svensson (NSS) yield-curve parameterisation.

NSS is the gold-standard parametric yield-curve model used by most
sovereign debt offices (Bundesbank, Banque de France, BoE). Six
parameters — beta0, beta1, beta2, beta3, tau1, tau2 — fit a smooth
curve that has a level (beta0), short-end slope (beta1), one hump
(beta2 / tau1), and a second hump (beta3 / tau2).

.. math::

    y(t) = \\beta_0
         + \\beta_1 \\cdot \\frac{1 - e^{-t/\\tau_1}}{t/\\tau_1}
         + \\beta_2 \\cdot \\left(\\frac{1 - e^{-t/\\tau_1}}{t/\\tau_1} - e^{-t/\\tau_1}\\right)
         + \\beta_3 \\cdot \\left(\\frac{1 - e^{-t/\\tau_2}}{t/\\tau_2} - e^{-t/\\tau_2}\\right)

The function below evaluates the NSS curve at a given maturity given
the six parameters; calibration of (beta_i, tau_j) from observed
yields is a separate caller-supplied step.

References
----------
Svensson, L. E. O. (1994). Estimating and Interpreting Forward
    Interest Rates: Sweden 1992-1994. *IMF Working Paper No. 94/114*.
Nelson, C. R., & Siegel, A. F. (1987). Parsimonious modeling of
    yield curves. *Journal of Business*, 60(4), 473-489.
"""

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class NssParameters:
    """Six-parameter NSS yield-curve descriptor."""

    beta0: float
    beta1: float
    beta2: float
    beta3: float
    tau1: float
    tau2: float

    def __post_init__(self) -> None:
        if self.tau1 <= 0 or self.tau2 <= 0:
            raise ValueError("NSS tau1 and tau2 must be positive")


def nss_yield(params: NssParameters, maturity_years: float) -> float:
    """Evaluate the NSS curve at [maturity_years]."""
    if maturity_years <= 0:
        # The instantaneous limit (t -> 0+) is beta0 + beta1; second
        # and third terms vanish.
        return params.beta0 + params.beta1
    t1 = maturity_years / params.tau1
    t2 = maturity_years / params.tau2
    factor1 = (1.0 - math.exp(-t1)) / t1
    factor2 = factor1 - math.exp(-t1)
    factor3 = (1.0 - math.exp(-t2)) / t2 - math.exp(-t2)
    return (
        params.beta0
        + params.beta1 * factor1
        + params.beta2 * factor2
        + params.beta3 * factor3
    )
