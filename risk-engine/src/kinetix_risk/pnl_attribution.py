"""Greek-component P&L attribution.

Decomposes a position (or book) daily P&L into the contribution of each Greek
using the standard Taylor-expansion identity:

    P&L  =  delta·ΔS              (directional / first order in spot)
          + ½·gamma·ΔS²           (convexity / second order in spot)
          + vega·Δσ               (volatility)
          + theta·Δt              (time decay)
          + rho·Δr                (interest rate)

This lets a desk see *whether a move was delta-, vega- or theta-driven* rather
than only the headline number.

Two dollar-denominated sensitivities are also reported:

    dollar-delta  =  delta·S      (cash exposure to a 100% spot move)
    dollar-gamma  =  gamma·S²     (cash convexity)

These are pure, side-effect-free calculations — Prometheus recording lives in
``pnl_attribution_metrics.record_greek_pnl_attribution``.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class PositionGreeks:
    """Greek sensitivities for a position or aggregated book.

    Greeks follow market convention:

    - ``delta``  — P&L per unit move in the underlying price.
    - ``gamma``  — change in delta per unit move in the underlying price.
    - ``vega``   — P&L per 1.00 (i.e. 100 vol-point) change in volatility.
    - ``theta``  — P&L per calendar day of time decay (typically negative).
    - ``rho``    — P&L per unit (1.00 = 100%) change in the risk-free rate.
    - ``spot``   — current underlying price, used for dollar-delta/gamma.
    """

    delta: float
    gamma: float
    vega: float
    theta: float
    rho: float
    spot: float


@dataclass(frozen=True)
class MarketMove:
    """The realised market move over the attribution window."""

    price_change: float          # ΔS — change in the underlying price
    vol_change: float            # Δσ — change in volatility (1.00 = 100 vol points)
    time_change_days: float      # Δt — calendar days elapsed
    rate_change: float           # Δr — change in the risk-free rate (1.00 = 100%)


@dataclass(frozen=True)
class GreekPnlAttribution:
    """Per-Greek P&L contribution plus dollar-delta / dollar-gamma."""

    delta_pnl: float
    gamma_pnl: float
    vega_pnl: float
    theta_pnl: float
    rho_pnl: float
    dollar_delta: float
    dollar_gamma: float

    @property
    def total_pnl(self) -> float:
        """Sum of every Greek component — the explained P&L."""
        return (
            self.delta_pnl
            + self.gamma_pnl
            + self.vega_pnl
            + self.theta_pnl
            + self.rho_pnl
        )


def decompose_greek_pnl(greeks: PositionGreeks, move: MarketMove) -> GreekPnlAttribution:
    """Decompose P&L into per-Greek components for a single market move.

    Applies the Taylor-expansion P&L identity (see module docstring) and also
    computes the dollar-delta / dollar-gamma cash sensitivities.
    """
    delta_pnl = greeks.delta * move.price_change
    gamma_pnl = 0.5 * greeks.gamma * move.price_change**2
    vega_pnl = greeks.vega * move.vol_change
    theta_pnl = greeks.theta * move.time_change_days
    rho_pnl = greeks.rho * move.rate_change

    dollar_delta = greeks.delta * greeks.spot
    dollar_gamma = greeks.gamma * greeks.spot**2

    return GreekPnlAttribution(
        delta_pnl=delta_pnl,
        gamma_pnl=gamma_pnl,
        vega_pnl=vega_pnl,
        theta_pnl=theta_pnl,
        rho_pnl=rho_pnl,
        dollar_delta=dollar_delta,
        dollar_gamma=dollar_gamma,
    )


def aggregate_book_greeks(
    delta_by_asset_class: dict,
    gamma_by_asset_class: dict,
    vega_by_asset_class: dict,
    theta: float,
    rho: float,
    spot: float,
) -> PositionGreeks:
    """Collapse per-asset-class Greeks into a single book-level :class:`PositionGreeks`.

    The VaR pipeline produces delta/gamma/vega as ``{AssetClass: value}`` maps
    while theta and rho are already book scalars. This sums the maps so the
    Greek-component P&L decomposition can run once for the whole book.
    """
    return PositionGreeks(
        delta=sum(delta_by_asset_class.values()),
        gamma=sum(gamma_by_asset_class.values()),
        vega=sum(vega_by_asset_class.values()),
        theta=theta,
        rho=rho,
        spot=spot,
    )
