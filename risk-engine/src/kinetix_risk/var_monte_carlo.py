"""
Monte Carlo Value at Risk.

Multi-day VaR is derived from 1-day VaR using the Basel square-root-of-time rule:

    VaR(T) = VaR(1) * sqrt(T)

Assumption: daily returns are i.i.d. (independent and identically distributed).
Each simulation path generates a single-day correlated return; the result is then
scaled to the target horizon via sqrt(T).

Validity: holds reasonably well for short horizons (1-10 days) in liquid markets.

Known limitation: understates risk when:
  - returns exhibit autocorrelation (positive serial correlation makes multi-day
    losses larger than sqrt(T) scaling implies),
  - volatility is time-varying / clustered (GARCH effects mean variance does not
    grow linearly with time), or
  - return distributions have fat tails or skew.

For more accurate multi-day VaR, simulate full T-day paths rather than scaling
the 1-day quantile.
"""

import numpy as np

from kinetix_risk.expected_shortfall import calculate_expected_shortfall
from kinetix_risk.models import AssetClassExposure, ConfidenceLevel, ComponentBreakdown, VaRResult

TRADING_DAYS_PER_YEAR = 252


def _nearest_positive_definite(matrix: np.ndarray) -> np.ndarray:
    """Spectral-clipping projection onto the nearest positive-definite matrix.

    The Monte Carlo path requires a Cholesky factor of the correlation
    matrix; a non-PSD input (numerical drift, an inconsistent
    rebuild) makes ``np.linalg.cholesky`` raise. This helper performs
    the simplest robust repair: eigendecompose, clip any eigenvalue
    below a tiny floor, then reconstruct.

    The full Higham (2002) algorithm — *Computing the Nearest
    Correlation Matrix — A Problem from Finance* (IMA J. Numer. Anal.,
    22(3), 329-343) — alternates the projection used here with a
    second projection that re-imposes a unit diagonal, iterating to
    convergence in the Frobenius norm. For our use case the single
    spectral clip is sufficient: the input is already nominally a
    correlation matrix, so we only need to neutralise the small
    negative-eigenvalue drift introduced by upstream rounding. The
    iterative version is recorded here for the reader as the
    canonical solution if drift ever becomes large enough to warrant
    it.

    References
    ----------
    Higham, N. J. (2002). Computing the nearest correlation matrix —
        a problem from finance. *IMA Journal of Numerical Analysis*,
        22(3), 329-343.
    """
    eigenvalues, eigenvectors = np.linalg.eigh(matrix)
    eigenvalues = np.maximum(eigenvalues, 1e-10)
    return eigenvectors @ np.diag(eigenvalues) @ eigenvectors.T


def calculate_monte_carlo_var(
    exposures: list[AssetClassExposure],
    confidence_level: ConfidenceLevel,
    time_horizon_days: int,
    correlation_matrix: np.ndarray,
    num_simulations: int = 10_000,
    seed: int | None = None,
) -> VaRResult:
    n = len(exposures)
    market_values = np.array([e.total_market_value for e in exposures])
    daily_vols = np.array([e.volatility / np.sqrt(TRADING_DAYS_PER_YEAR) for e in exposures])

    rng = np.random.default_rng(seed)

    # Simulate correlated returns via Cholesky decomposition
    try:
        cholesky = np.linalg.cholesky(correlation_matrix)
    except np.linalg.LinAlgError:
        repaired = _nearest_positive_definite(correlation_matrix)
        try:
            cholesky = np.linalg.cholesky(repaired)
        except np.linalg.LinAlgError:
            raise ValueError(
                "correlation matrix is not positive-definite and could not be repaired"
            )
    z = rng.standard_normal((num_simulations, n))

    # Original paths
    correlated_returns = z @ cholesky.T * daily_vols
    # Antithetic paths (using -Z) for variance reduction
    correlated_returns_anti = -z @ cholesky.T * daily_vols

    # Combine original and antithetic paths (2N total)
    correlated_returns_combined = np.concatenate([correlated_returns, correlated_returns_anti])

    # Portfolio losses (positive = loss)
    portfolio_losses = -(correlated_returns_combined @ market_values)

    # 1-day VaR at confidence level
    alpha = confidence_level.value
    var_1d = float(np.percentile(portfolio_losses, alpha * 100))
    # Basel sqrt(T) rule: scales 1-day VaR to T-day VaR under the i.i.d. normally
    # distributed returns assumption.  Valid for short horizons (1-10 days);
    # understates risk under autocorrelation, volatility clustering, or fat tails.
    var_value = var_1d * np.sqrt(time_horizon_days)

    # Expected shortfall from simulated distribution
    es_1d = calculate_expected_shortfall(portfolio_losses, confidence_level)
    es_value = es_1d * np.sqrt(time_horizon_days)

    # Component breakdown: individual asset class losses (combined paths)
    individual_losses = -(correlated_returns_combined * market_values)
    component_var_1d = []
    for i in range(n):
        asset_losses = individual_losses[:, i]
        cv = float(np.percentile(asset_losses, alpha * 100))
        component_var_1d.append(cv)

    total_component = sum(component_var_1d) if sum(component_var_1d) > 0 else 1.0
    breakdown = []
    for i, exp in enumerate(exposures):
        cv = component_var_1d[i] * np.sqrt(time_horizon_days)
        pct = (component_var_1d[i] / total_component * 100) if total_component > 0 else 0.0
        breakdown.append(ComponentBreakdown(exp.asset_class, float(cv), float(pct)))

    return VaRResult(float(var_value), float(es_value), breakdown)
