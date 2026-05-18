"""
Property-based tests for the bond pricing functions.

Each test verifies a *mathematical invariant* of the discounted-cash-flow
bond pricer that holds for any well-formed bond, rather than a specific
arithmetic result. These complement the example-based tests in
[test_bond_pricing.py].

The production module exposes `bond_pv(bond, yield_rate)` and
`bond_modified_duration(bond, yield_rate)`. The yield-to-maturity inverse
and the convexity sensitivity are computed numerically inside the test
file (brentq for YTM, central second difference for convexity) so the
properties exercise the production pricer without modifying production
code.
"""
from __future__ import annotations

from datetime import date, timedelta

import pytest
from hypothesis import HealthCheck, assume, given, settings
from hypothesis import strategies as st
from scipy.optimize import brentq

from kinetix_risk.bond_pricing import bond_modified_duration, bond_pv
from kinetix_risk.models import AssetClass, BondPosition


pytestmark = pytest.mark.unit


# Plausible domain ranges for bond inputs.  Bounds are picked to avoid
# pathological corners (zero maturity, zero face, negative yield) where the
# DCF formula degenerates or where brentq has no bracket.
coupon_strategy = st.floats(min_value=0.0, max_value=0.15, allow_nan=False)
maturity_years_strategy = st.floats(
    min_value=0.5, max_value=30.0, allow_nan=False
)
face_strategy = st.floats(min_value=50.0, max_value=1_000.0, allow_nan=False)
yield_strategy = st.floats(min_value=0.001, max_value=0.20, allow_nan=False)
frequency_strategy = st.sampled_from([1, 2, 4])


def _make_bond(
    coupon: float, maturity_years: float, face: float, frequency: int
) -> BondPosition:
    """Build a BondPosition whose ``_years_to_maturity`` resolves to
    approximately ``maturity_years`` from today.
    """
    maturity_date = date.today() + timedelta(days=int(maturity_years * 365.25))
    return BondPosition(
        instrument_id="BOND-INV",
        asset_class=AssetClass.FIXED_INCOME,
        market_value=face,
        currency="USD",
        face_value=face,
        coupon_rate=coupon,
        coupon_frequency=frequency,
        maturity_date=maturity_date.isoformat(),
    )


def _ytm_from_price(bond: BondPosition, target_price: float) -> float:
    """Recover yield-to-maturity by bracketing ``bond_pv(bond, y) - target_price``.

    The DCF price is strictly decreasing in ``y`` over the bracket, so brentq
    converges to the unique root.  Brackets [1e-6, 5.0] cover all realistic
    yields and leave headroom for deep-OTM/low-coupon bonds.
    """
    def f(y: float) -> float:
        return bond_pv(bond, y) - target_price

    return brentq(f, 1e-6, 5.0, xtol=1e-10, rtol=1e-12, maxiter=200)


def _bond_convexity(bond: BondPosition, yield_rate: float) -> float:
    """Numerical convexity via the standard central second difference.

    convexity ≈ (PV(y+h) - 2·PV(y) + PV(y-h)) / (PV(y) · h²)

    Returns 0.0 for degenerate cases (PV near zero) so the non-negativity
    property is well-defined on the entire input domain.
    """
    h = 1e-4
    pv = bond_pv(bond, yield_rate)
    if abs(pv) < 1e-12:
        return 0.0
    pv_up = bond_pv(bond, yield_rate + h)
    pv_down = bond_pv(bond, yield_rate - h)
    return (pv_up - 2.0 * pv + pv_down) / (pv * h * h)


@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.too_slow])
@given(
    coupon=coupon_strategy,
    maturity_years=maturity_years_strategy,
    face=face_strategy,
    frequency=frequency_strategy,
    y=yield_strategy,
)
def test_price_yield_round_trip(coupon, maturity_years, face, frequency, y):
    """`price(ytm(price(y))) ≈ price(y)`.

    Recovering the yield from a price and re-pricing must reproduce the
    original price.  Tolerance is loose because brentq is iterative and the
    pricer's coupon count is quantised by ``int(years * freq)``.
    """
    bond = _make_bond(coupon, maturity_years, face, frequency)
    original_price = bond_pv(bond, y)
    # Bonds with maturity rounding to zero periods degenerate to face; skip.
    assume(original_price > 1e-9)

    recovered_yield = _ytm_from_price(bond, original_price)
    recovered_price = bond_pv(bond, recovered_yield)

    # Loose tolerance — brentq xtol plus quantisation in the DCF.
    tol = max(1e-4, 1e-6 * original_price)
    assert abs(recovered_price - original_price) < tol, (
        f"round-trip drift: original={original_price}, "
        f"recovered_yield={recovered_yield}, recovered_price={recovered_price}"
    )


@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.too_slow])
@given(
    coupon=coupon_strategy,
    maturity_years=maturity_years_strategy,
    face=face_strategy,
    frequency=frequency_strategy,
    y=yield_strategy,
)
def test_price_decreases_in_yield(coupon, maturity_years, face, frequency, y):
    """Bond price is monotone non-increasing in yield.

    A yield bump at any point in the valid domain must not raise the PV.
    The DCF formula multiplies every cash flow by ``1 / (1 + r)^t`` with
    positive ``t``, so the derivative is non-positive everywhere.
    """
    assume(y < 0.19)  # leave headroom for the bump
    bond = _make_bond(coupon, maturity_years, face, frequency)
    pv_base = bond_pv(bond, y)
    pv_bumped = bond_pv(bond, y + 0.001)

    # Equality is permitted when the bond has effectively zero remaining
    # cash flow (e.g. very short residual after period quantisation).
    assert pv_bumped <= pv_base + 1e-9, (
        f"PV increased with yield bump: y={y}, pv_base={pv_base}, "
        f"pv_bumped={pv_bumped}"
    )


@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.too_slow])
@given(
    coupon=coupon_strategy,
    maturity_years=maturity_years_strategy,
    face=face_strategy,
    frequency=frequency_strategy,
    y=yield_strategy,
)
def test_modified_duration_non_negative(coupon, maturity_years, face, frequency, y):
    """Modified duration is defined as ``DV01 · 10_000 / PV``.

    ``bond_dv01`` returns ``abs(pv_down - pv_up) / 2`` (always non-negative),
    PV is non-negative for any bond with non-negative coupon and face, so
    the ratio cannot be negative.
    """
    bond = _make_bond(coupon, maturity_years, face, frequency)
    md = bond_modified_duration(bond, y)
    assert md >= -1e-12, f"negative modified duration: {md}"


@settings(max_examples=100, deadline=None, suppress_health_check=[HealthCheck.too_slow])
@given(
    coupon=coupon_strategy,
    maturity_years=maturity_years_strategy,
    face=face_strategy,
    frequency=frequency_strategy,
    y=yield_strategy,
)
def test_convexity_non_negative(coupon, maturity_years, face, frequency, y):
    """Convexity is the second derivative of PV in yield, normalised by PV.

    For any series of positive cash flows discounted at a flat positive
    yield, the second derivative is strictly positive: each cash flow
    contributes ``t(t+1)·CF / (1+r)^(t+2)`` to the second derivative.
    The normalised convexity is therefore non-negative everywhere.
    """
    # Stay inside the domain even after the central-difference bump.
    assume(y > 0.002 and y < 0.19)
    bond = _make_bond(coupon, maturity_years, face, frequency)
    convexity = _bond_convexity(bond, y)
    assert convexity >= -1e-6, (
        f"negative convexity: {convexity} for coupon={coupon}, "
        f"maturity_years={maturity_years}, face={face}, freq={frequency}, y={y}"
    )
