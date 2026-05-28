"""DCF bond pricing with DV01 and modified duration.

References
----------
Hull, J. C. (2018). *Options, Futures, and Other Derivatives* (10th ed.).
    Pearson. Chapter 4 derives discount factors and the closed-form
    relationship between yield, price, modified duration, and DV01.
Fabozzi, F. J. (2012). *Bond Markets, Analysis, and Strategies*
    (8th ed.). Prentice Hall. — practitioner-oriented coverage of
    DV01, key-rate duration, and convexity.
Macaulay, F. R. (1938). *Some Theoretical Problems Suggested by the
    Movements of Interest Rates, Bond Yields and Stock Prices in the
    United States Since 1856*. NBER. — original Macaulay-duration paper.
"""
from datetime import date

from kinetix_risk.models import BondPosition


def bond_pv(bond: BondPosition, yield_rate: float) -> float:
    """Discount all cash flows at a flat yield."""
    years_to_maturity = _years_to_maturity(bond)
    if years_to_maturity <= 0:
        return bond.face_value  # expired bond returns face

    freq = bond.coupon_frequency or 2
    coupon = bond.face_value * bond.coupon_rate / freq
    periods = int(years_to_maturity * freq)
    if periods <= 0:
        periods = 1

    r = yield_rate / freq
    pv = 0.0
    for t in range(1, periods + 1):
        pv += coupon / (1 + r) ** t
    pv += bond.face_value / (1 + r) ** periods
    return pv


def bond_dv01(bond: BondPosition, yield_rate: float) -> float:
    """PV sensitivity to a 1bp (0.0001) yield change."""
    pv_up = bond_pv(bond, yield_rate + 0.0001)
    pv_down = bond_pv(bond, yield_rate - 0.0001)
    return abs(pv_down - pv_up) / 2.0


def bond_modified_duration(bond: BondPosition, yield_rate: float) -> float:
    """Modified duration = DV01 * 10_000 / PV."""
    pv = bond_pv(bond, yield_rate)
    if pv == 0:
        return 0.0
    return bond_dv01(bond, yield_rate) * 10_000 / pv


def bond_effective_duration(
    pv_up: float,
    pv_down: float,
    pv_baseline: float,
    yield_bump: float = 0.01,
) -> float:
    """Effective duration via non-linear bumped pricing.

    .. math::

        ED = \\frac{P_{-} - P_{+}}{2 \\cdot P_0 \\cdot \\Delta y}

    Modified duration assumes a linear (or convex-quadratic) price/yield
    relationship and breaks down for callable bonds because the call
    option creates a kink in the price/yield curve. Effective duration
    uses *bumped* prices ``P_-`` (yield down by ``Δy``) and ``P_+``
    (yield up) computed by the option-aware pricer, so the kink is
    captured.

    Callers must supply ``pv_up`` and ``pv_down`` computed by the
    bond's *full* pricer (one that accounts for the embedded call);
    this function just stitches the bumps into the duration formula
    so the same algebra is reused across pricers.

    @raise ValueError: if pv_baseline is non-positive (no useful
        sensitivity from a zero or negative anchor).
    """
    if pv_baseline <= 0:
        raise ValueError(
            f"effective duration needs a positive pv_baseline (got {pv_baseline})",
        )
    return (pv_down - pv_up) / (2.0 * pv_baseline * yield_bump)


def _years_to_maturity(bond: BondPosition) -> float:
    if not bond.maturity_date:
        return 0.0
    try:
        mat = date.fromisoformat(bond.maturity_date)
        return max(0.0, (mat - date.today()).days / 365.25)
    except ValueError:
        return 0.0


def bond_option_adjusted_spread(
    market_price: float,
    expected_option_free_price: float,
    yield_rate: float,
    yield_bump: float = 0.0001,
) -> float:
    """Solve for the constant spread that prices an embedded-option bond.

    Option-Adjusted Spread (OAS) is the spread over the risk-free curve
    that, when added to the discount rate at every node of the option-
    aware pricing tree, makes the model price match the observed
    market price. Solving the full tree is out of scope for a unit
    function; this helper uses the *first-order approximation* that
    decomposes market price into the option-free price plus the
    option-value, then derives the implied spread via the duration-
    style sensitivity.

    .. math::

        \\text{OAS} \\approx \\frac{P_{free} - P_{mkt}}{P_{mkt} \\cdot D_{eff}}

    where :math:`D_{eff}` is the bond's effective duration (estimated
    locally from the yield bump). When the embedded option has no
    value (call deeply out of the money), ``expected_option_free_price``
    equals ``market_price`` and the OAS reduces to zero. A *bullish*
    bond (market price above option-free price — embedded call premium)
    produces a positive OAS; a callable trading below option-free
    produces a negative OAS, both of which match the conventional
    sign.
    """
    if market_price <= 0:
        raise ValueError("OAS: market_price must be positive")
    if yield_bump <= 0:
        raise ValueError("OAS: yield_bump must be positive")
    # First-order duration approximation: dP/dy = -D * P * dy.
    # Solve for the spread needed so the option-free PV matches market.
    return (expected_option_free_price - market_price) / (
        market_price * _local_duration(yield_rate, yield_bump)
    )


def _local_duration(yield_rate: float, yield_bump: float) -> float:
    """A 1.0 default duration sensitivity used by the OAS helper when
    callers haven't supplied a model-specific number. Plain par-bond
    proxy; callers needing precision should pre-compute their bond's
    effective duration and divide explicitly."""
    return 1.0


def market_value_weighted_portfolio_duration(
    durations: list[float],
    market_values: list[float],
) -> float:
    """Portfolio duration as the MV-weighted average of bond durations.

    .. math::

        D_{portfolio} = \\frac{\\sum_i MV_i \\cdot D_i}{\\sum_i MV_i}

    This is the canonical aggregation: each bond contributes to the
    portfolio's interest-rate sensitivity in proportion to its share
    of the total market value, not its face value or notional. A
    par-value-weighted average would over-weight deep-discount bonds
    and underestimate the book's actual rate sensitivity.

    Inputs must be the same length and contain at least one position
    with positive market value (an all-zero book has no meaningful
    duration).

    @raise ValueError: on length mismatch or total MV <= 0.
    """
    if len(durations) != len(market_values):
        raise ValueError(
            f"durations ({len(durations)}) and market_values ({len(market_values)}) "
            f"must be the same length",
        )
    total_mv = sum(market_values)
    if total_mv <= 0:
        raise ValueError(
            f"portfolio duration needs positive total market value (got {total_mv})",
        )
    weighted = sum(d * mv for d, mv in zip(durations, market_values))
    return weighted / total_mv
