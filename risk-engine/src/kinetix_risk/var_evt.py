"""Extreme Value Theory (EVT) tail VaR via the Generalised Pareto
Distribution.

Parametric VaR assumes the return distribution is normal — under-
states tail risk during regime breaks (Black-Monday-1987 was a
~22-sigma move under a normal model). EVT side-steps the
distributional assumption by fitting a Generalised Pareto
Distribution (GPD) directly to the *excess losses over a high
threshold*, then extrapolates from that fitted tail to the desired
confidence level.

The GPD has two parameters:
  - xi (shape) — controls tail heaviness. xi > 0 is fat-tailed
    (financial assets typically xi ~ 0.1-0.3). xi = 0 is exponential
    (Gumbel limit); xi < 0 is bounded-tail (Beta limit).
  - beta (scale) — sets the typical magnitude of an excess.

Reference
---------
McNeil, A. J., Frey, R., & Embrechts, P. (2015). *Quantitative
Risk Management* (2nd ed.). Princeton. Chapter 7 covers GPD fitting
and EVT VaR.
"""

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class GpdFit:
    """Generalised Pareto fit to excess losses over a threshold."""

    threshold: float
    xi: float
    beta: float
    n_total: int
    n_exceedances: int


def fit_gpd_to_excesses(
    losses: list[float],
    threshold: float,
) -> GpdFit:
    """Fit a GPD to losses above [threshold] via method of moments.

    Method of moments uses the first two sample moments of the
    excesses to back out (xi, beta). Full maximum-likelihood
    estimation gives marginally better fits but the method-of-moments
    estimator is closed-form and quick to compute, which is what the
    risk-engine needs for an interactive recalc.
    """
    excesses = [l - threshold for l in losses if l > threshold]
    n_exc = len(excesses)
    if n_exc < 2:
        # Degenerate fit: not enough exceedances to estimate two params.
        return GpdFit(threshold=threshold, xi=0.0, beta=0.0, n_total=len(losses), n_exceedances=n_exc)
    mean = sum(excesses) / n_exc
    var = sum((e - mean) ** 2 for e in excesses) / n_exc
    if var == 0:
        return GpdFit(threshold=threshold, xi=0.0, beta=mean, n_total=len(losses), n_exceedances=n_exc)
    # Method-of-moments: xi = 0.5 * (1 - mean^2 / var), beta = 0.5 * mean * (1 + mean^2/var).
    xi = 0.5 * (1.0 - mean * mean / var)
    beta = 0.5 * mean * (1.0 + mean * mean / var)
    return GpdFit(threshold=threshold, xi=xi, beta=beta, n_total=len(losses), n_exceedances=n_exc)


def evt_var(fit: GpdFit, confidence: float) -> float:
    """Compute the EVT VaR at the requested confidence level from a
    fitted GPD.

    .. math::

        \\text{VaR}_q = u + \\frac{\\beta}{\\xi}\\left[\\left(\\frac{n}{N_u}(1-q)\\right)^{-\\xi} - 1\\right]

    where ``u`` is the threshold, ``N_u`` is the number of
    exceedances, ``n`` is the total sample size, and ``q`` is the
    confidence level.

    @raise ValueError: if confidence is outside (0, 1).
    """
    if not 0 < confidence < 1:
        raise ValueError(f"confidence must be in (0, 1) (got {confidence})")
    if fit.n_exceedances == 0:
        return fit.threshold
    n_ratio = fit.n_total / fit.n_exceedances
    tail_prob = 1.0 - confidence
    if abs(fit.xi) < 1e-9:
        # Limit xi -> 0: VaR = u + beta * ln(N_u / (n * (1 - q))).
        return fit.threshold - fit.beta * math.log(n_ratio * tail_prob)
    inner = (n_ratio * tail_prob) ** (-fit.xi) - 1.0
    return fit.threshold + (fit.beta / fit.xi) * inner
