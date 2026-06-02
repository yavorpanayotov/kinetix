from __future__ import annotations

from typing import TYPE_CHECKING

from kinetix_risk.greeks import calculate_greeks
from kinetix_risk.models import (
    BondPosition,
    CalculationType,
    ConfidenceLevel,
    OptionPosition,
    PositionRisk,
    SwapPosition,
    ValuationResult,
)
from kinetix_risk.portfolio_risk import calculate_book_var
from kinetix_risk.position_resolver import resolve_positions
from kinetix_risk.volatility import VolatilityProvider

if TYPE_CHECKING:
    from kinetix_risk.market_data_consumer import MarketDataBundle

_DEFAULT_OUTPUTS = ["VAR", "EXPECTED_SHORTFALL"]


def calculate_valuation(
    positions: list[PositionRisk],
    calculation_type: CalculationType,
    confidence_level: ConfidenceLevel,
    time_horizon_days: int,
    requested_outputs: list[str] | None = None,
    num_simulations: int = 10_000,
    volatility_provider: VolatilityProvider | None = None,
    correlation_matrix=None,
    book_id: str = "",
    seed: int | None = None,
    market_data_bundle: "MarketDataBundle | None" = None,
) -> ValuationResult:
    outputs = requested_outputs if requested_outputs else _DEFAULT_OUTPUTS

    if not positions:
        return ValuationResult(var_result=None, greeks_result=None, computed_outputs=[], pv_value=None)

    # Resolve typed positions to linear exposures (e.g., delta-adjusted for options).
    # Pass the market data bundle so that options with missing spot/vol can be enriched.
    resolved, degradation_flags = resolve_positions(positions, bundle=market_data_bundle)

    need_var = "VAR" in outputs or "EXPECTED_SHORTFALL" in outputs
    need_greeks = "GREEKS" in outputs
    need_pv = "PV" in outputs

    var_result = None
    greeks_result = None
    pv_value = None
    computed = []

    historical_returns = market_data_bundle.historical_returns if market_data_bundle else None

    if need_var:
        if calculation_type == CalculationType.HISTORICAL and historical_returns is None:
            degradation_flags.append("HISTORICAL_RETURNS_UNAVAILABLE")
        var_result = calculate_book_var(
            positions=resolved,
            calculation_type=calculation_type,
            confidence_level=confidence_level,
            time_horizon_days=time_horizon_days,
            num_simulations=num_simulations,
            volatility_provider=volatility_provider or VolatilityProvider.static(),
            correlation_matrix=correlation_matrix,
            historical_returns=historical_returns,
            seed=seed,
        )
        if "VAR" in outputs:
            computed.append("VAR")
        if "EXPECTED_SHORTFALL" in outputs:
            computed.append("EXPECTED_SHORTFALL")

    if need_greeks:
        base_var = var_result.var_value if var_result is not None else None
        greeks_result = calculate_greeks(
            positions=resolved,
            calculation_type=calculation_type,
            confidence_level=confidence_level,
            time_horizon_days=time_horizon_days,
            book_id=book_id,
            base_var_value=base_var,
            volatility_provider=volatility_provider or VolatilityProvider.static(),
            correlation_matrix=correlation_matrix,
            historical_returns=historical_returns,
            num_simulations=num_simulations,
            seed=seed,
        )
        computed.append("GREEKS")

    if need_pv:
        pv_value = _calculate_model_pv(positions, market_data_bundle, degradation_flags)
        computed.append("PV")

    # Compute per-position analytical Black-Scholes Greeks for any OptionPosition
    # that has valid market data.  We compute against the (possibly enriched) originals,
    # not the resolved linear exposures, because we want option Greeks, not equity proxy.
    position_greeks: dict | None = None
    if need_greeks:
        pg: dict[str, dict[str, float]] = {}
        # Walk the original positions; enrich using the bundle if we were given one
        from kinetix_risk.position_resolver import _enrich_option
        for pos in positions:
            if isinstance(pos, OptionPosition):
                enriched, _ = _enrich_option(pos, market_data_bundle)
                if enriched.spot_price > 0 and enriched.implied_vol > 0 and enriched.expiry_days > 0:
                    from kinetix_risk.black_scholes import bs_greeks
                    pg[enriched.instrument_id] = bs_greeks(enriched)
        if pg:
            position_greeks = pg

    return ValuationResult(
        var_result=var_result,
        greeks_result=greeks_result,
        computed_outputs=computed,
        pv_value=pv_value,
        position_greeks=position_greeks,
        degradation_flags=list(degradation_flags),
    )


def _calculate_model_pv(
    positions: list[PositionRisk],
    bundle: "MarketDataBundle | None",
    flags: list[str],
) -> float:
    """Compute portfolio PV using pricing models where available, falling back to market_value."""
    total = 0.0
    for pos in positions:
        if isinstance(pos, BondPosition) and bundle is not None:
            yc = bundle.yield_curves.get(pos.currency)
            if yc is not None:
                from kinetix_risk.bond_pricing import bond_pv
                total += bond_pv(pos, yc.rate_at(365))
                continue
            else:
                flags.append(f"YIELD_CURVE_MISSING:{pos.currency}")
        elif isinstance(pos, SwapPosition) and bundle is not None:
            yc = bundle.yield_curves.get(pos.currency)
            if yc is not None:
                from kinetix_risk.swap_pricing import swap_pv
                total += swap_pv(pos, yc)
                continue
            else:
                flags.append(f"YIELD_CURVE_MISSING:{pos.currency}")
        elif isinstance(pos, OptionPosition) and bundle is not None:
            from kinetix_risk.position_resolver import _enrich_option
            enriched, _ = _enrich_option(pos, bundle)
            if enriched.spot_price > 0 and enriched.implied_vol > 0 and enriched.expiry_days > 0:
                from kinetix_risk.black_scholes import bs_price
                total += bs_price(enriched) * enriched.quantity * enriched.contract_multiplier
                continue
        total += pos.market_value
    return total
