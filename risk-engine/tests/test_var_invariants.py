"""
Property-based tests for parametric Value at Risk.

Each test verifies a *mathematical invariant* that holds for any well-formed
portfolio, rather than a specific arithmetic result. These complement the
example-based tests in [test_var_parametric.py] and the dedicated sqrt(T)
spec tests in [test_var_sqrt_t_scaling.py].

VaR calls are heavier than Black-Scholes price evaluations (they assemble a
covariance matrix and run an Euler allocation), so example budgets are capped
at 100 per property.
"""
from __future__ import annotations

import math

import numpy as np
import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from kinetix_risk.models import (
    AssetClass,
    AssetClassExposure,
    ConfidenceLevel,
)
from kinetix_risk.var_parametric import calculate_parametric_var


pytestmark = pytest.mark.unit


# Asset classes are sampled (with replacement) for each exposure slot.  The
# parametric formula doesn't branch on asset class, so repeats are harmless.
ASSET_CLASSES = list(AssetClass)

# Plausible domain ranges.  Positive volatility avoids the degenerate zero-vol
# case which would make every property trivially true.  Strictly positive
# market values keep port_std bounded away from zero, which sharpens the
# numerical comparison in the sqrt(T) ratio test.
market_value_strategy = st.floats(min_value=1_000.0, max_value=10_000_000.0, allow_nan=False)
volatility_strategy = st.floats(min_value=0.01, max_value=1.50, allow_nan=False)
n_assets_strategy = st.integers(min_value=1, max_value=5)
horizon_strategy = st.integers(min_value=1, max_value=252)


def _portfolio_strategy() -> st.SearchStrategy[tuple[list[AssetClassExposure], np.ndarray]]:
    """Generate a (well-formed exposures, valid correlation matrix) pair.

    The correlation matrix is produced from a random matrix A via
    C = A @ A.T  normalised by its diagonal, which guarantees a symmetric
    positive-semi-definite matrix with unit diagonal — the contract the
    parametric VaR engine expects.  A small ridge is added to keep the
    minimum eigenvalue strictly positive and avoid pathological numerics.
    """

    @st.composite
    def _build(draw):
        n = draw(n_assets_strategy)
        exposures: list[AssetClassExposure] = []
        for _ in range(n):
            ac = draw(st.sampled_from(ASSET_CLASSES))
            mv = draw(market_value_strategy)
            vol = draw(volatility_strategy)
            exposures.append(AssetClassExposure(ac, mv, vol))

        # Deterministic correlation derived from a hypothesis-supplied seed so
        # the test is reproducible under shrinking.
        seed = draw(st.integers(min_value=0, max_value=2**31 - 1))
        rng = np.random.default_rng(seed)
        a = rng.standard_normal(size=(n, n))
        cov_like = a @ a.T + 1e-3 * np.eye(n)  # ridge -> strictly PD
        d = np.sqrt(np.diag(cov_like))
        corr = cov_like / np.outer(d, d)
        # Numerical hygiene: clip the diagonal to exactly 1.0 and symmetrise.
        np.fill_diagonal(corr, 1.0)
        corr = (corr + corr.T) / 2.0
        return exposures, corr

    return _build()


portfolio_strategy = _portfolio_strategy()


@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.too_slow])
@given(portfolio=portfolio_strategy, horizon=horizon_strategy)
def test_var_magnitude_monotone_in_confidence_level(portfolio, horizon):
    """VaR is non-decreasing in confidence level: VaR(0.99) >= VaR(0.975) >= VaR(0.95).

    This follows directly from the parametric formula var = z * sigma * sqrt(T)
    and the monotonicity of the normal quantile function in alpha.
    """
    exposures, corr = portfolio
    var_95 = calculate_parametric_var(exposures, ConfidenceLevel.CL_95, horizon, corr).var_value
    var_975 = calculate_parametric_var(exposures, ConfidenceLevel.CL_975, horizon, corr).var_value
    var_99 = calculate_parametric_var(exposures, ConfidenceLevel.CL_99, horizon, corr).var_value

    # Tiny epsilon absorbs floating-point round-off on identical-portfolio paths.
    eps = 1e-9 * max(1.0, abs(var_99))
    assert var_99 + eps >= var_975, f"VaR(0.99)={var_99} < VaR(0.975)={var_975}"
    assert var_975 + eps >= var_95, f"VaR(0.975)={var_975} < VaR(0.95)={var_95}"


@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.too_slow])
@given(
    portfolio=portfolio_strategy,
    t1=horizon_strategy,
    t2=horizon_strategy,
    confidence=st.sampled_from(list(ConfidenceLevel)),
)
def test_var_scales_as_sqrt_t(portfolio, t1, t2, confidence):
    """VaR(T2) / VaR(T1) == sqrt(T2 / T1) for any well-formed portfolio.

    The Basel sqrt(T) rule is an exact algebraic property of the parametric
    formula (the 1-day port_std is multiplied by sqrt(time_horizon_days)), so
    the ratio is tight to machine precision.
    """
    exposures, corr = portfolio
    var_t1 = calculate_parametric_var(exposures, confidence, t1, corr).var_value
    var_t2 = calculate_parametric_var(exposures, confidence, t2, corr).var_value

    # If port_std rounds to zero the ratio is 0/0; the test is uninformative
    # in that corner, but the generators guarantee strictly positive vols and
    # market values, so var_t1 is positive.
    expected_ratio = math.sqrt(t2 / t1)
    actual_ratio = var_t2 / var_t1
    assert math.isclose(actual_ratio, expected_ratio, rel_tol=1e-9, abs_tol=1e-12), (
        f"sqrt(T) violated: VaR(T2={t2})/VaR(T1={t1})={actual_ratio}, "
        f"expected sqrt({t2}/{t1})={expected_ratio}"
    )


@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.too_slow])
@given(
    portfolio=portfolio_strategy,
    horizon=horizon_strategy,
    confidence=st.sampled_from(list(ConfidenceLevel)),
)
def test_var_is_non_negative(portfolio, horizon, confidence):
    """Parametric VaR is non-negative (loss-magnitude convention).

    Formula: var = norm.ppf(alpha) * port_std * sqrt(T).  For alpha in {0.95,
    0.975, 0.99} the quantile is strictly positive; port_std is a quadratic
    form against a positive-semi-definite matrix and hence non-negative; sqrt(T)
    is positive.  Their product is therefore non-negative.
    """
    exposures, corr = portfolio
    result = calculate_parametric_var(exposures, confidence, horizon, corr)
    assert result.var_value >= 0.0, (
        f"negative VaR: {result.var_value} for confidence={confidence}, horizon={horizon}"
    )
