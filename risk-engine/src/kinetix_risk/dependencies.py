from dataclasses import dataclass, field

from kinetix_risk.models import (
    AssetClass,
    BondPosition,
    FuturePosition,
    FxPosition,
    OptionPosition,
    PositionRisk,
    SwapPosition,
)


@dataclass(frozen=True)
class MarketDataDependency:
    data_type: str
    instrument_id: str
    asset_class: str
    required: bool
    description: str
    parameters: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class _DependencyTemplate:
    data_type: str
    per_instrument: bool
    required: bool
    description: str
    parameters: dict[str, str] = field(default_factory=dict)
    currency_parameter: str | None = None


# Legacy asset-class-keyed registry (used as fallback for untyped positions)
DEPENDENCIES_REGISTRY: dict[AssetClass, list[_DependencyTemplate]] = {
    AssetClass.EQUITY: [
        _DependencyTemplate(
            data_type="SPOT_PRICE",
            per_instrument=True,
            required=True,
            description="Current spot price for equity position valuation",
        ),
        _DependencyTemplate(
            data_type="HISTORICAL_PRICES",
            per_instrument=True,
            required=False,
            description="Historical price series for volatility estimation",
            parameters={"lookbackDays": "252"},
        ),
    ],
    AssetClass.FIXED_INCOME: [
        _DependencyTemplate(
            data_type="YIELD_CURVE",
            per_instrument=False,
            required=True,
            description="Risk-free yield curve for discounting cash flows",
            currency_parameter="curveId",
        ),
        _DependencyTemplate(
            data_type="CREDIT_SPREAD",
            per_instrument=True,
            required=True,
            description="Credit spread for issuer-specific risk pricing",
        ),
    ],
    AssetClass.FX: [
        _DependencyTemplate(
            data_type="SPOT_PRICE",
            per_instrument=True,
            required=True,
            description="Current FX spot rate",
        ),
        _DependencyTemplate(
            data_type="FORWARD_CURVE",
            per_instrument=True,
            required=False,
            description="FX forward curve for forward rate estimation",
        ),
    ],
    AssetClass.COMMODITY: [
        _DependencyTemplate(
            data_type="SPOT_PRICE",
            per_instrument=True,
            required=True,
            description="Current commodity spot price",
        ),
        _DependencyTemplate(
            data_type="FORWARD_CURVE",
            per_instrument=True,
            required=False,
            description="Commodity forward curve for futures pricing",
        ),
    ],
    AssetClass.DERIVATIVE: [
        _DependencyTemplate(
            data_type="SPOT_PRICE",
            per_instrument=True,
            required=True,
            description="Underlying spot price for derivative valuation",
        ),
        _DependencyTemplate(
            data_type="VOLATILITY_SURFACE",
            per_instrument=True,
            required=True,
            description="Implied volatility surface for option pricing",
        ),
        _DependencyTemplate(
            data_type="RISK_FREE_RATE",
            per_instrument=False,
            required=True,
            description="Risk-free interest rate for discounting",
            currency_parameter="currency",
        ),
        _DependencyTemplate(
            data_type="DIVIDEND_YIELD",
            per_instrument=True,
            required=False,
            description="Expected dividend yield for underlying asset",
        ),
    ],
}


# ---------------------------------------------------------------------------
# Instrument-type-keyed emitters
# ---------------------------------------------------------------------------
# Each emitter receives a typed position and returns a list of dependencies.
# This approach handles cases that templates cannot: underlying_id lookups,
# multi-currency yield curves, and instrument-specific required/optional flags.


def _emit_cash_equity(pos: PositionRisk) -> list[MarketDataDependency]:
    return [
        MarketDataDependency("SPOT_PRICE", pos.instrument_id, pos.asset_class.value, True,
                             "Current spot price for equity position valuation"),
        MarketDataDependency("HISTORICAL_PRICES", pos.instrument_id, pos.asset_class.value, False,
                             "Historical price series for volatility estimation",
                             {"lookbackDays": "252"}),
    ]


def _emit_equity_option(pos: PositionRisk) -> list[MarketDataDependency]:
    underlying = pos.underlying_id if isinstance(pos, OptionPosition) else pos.instrument_id
    ac = pos.asset_class.value
    return [
        MarketDataDependency("SPOT_PRICE", underlying, ac, True,
                             "Underlying spot price for option valuation"),
        MarketDataDependency("VOLATILITY_SURFACE", underlying, ac, True,
                             "Implied volatility surface for option pricing"),
        MarketDataDependency("RISK_FREE_RATE", "", ac, True,
                             "Risk-free interest rate for discounting",
                             {"currency": pos.currency}),
        MarketDataDependency("DIVIDEND_YIELD", underlying, ac, False,
                             "Expected dividend yield for underlying asset"),
        MarketDataDependency("HISTORICAL_PRICES", underlying, ac, False,
                             "Historical price series of underlying for VaR",
                             {"lookbackDays": "252"}),
    ]


def _emit_equity_future(pos: PositionRisk) -> list[MarketDataDependency]:
    underlying = pos.underlying_id if isinstance(pos, FuturePosition) else pos.instrument_id
    ac = pos.asset_class.value
    return [
        MarketDataDependency("SPOT_PRICE", underlying, ac, True,
                             "Underlying spot price for futures pricing"),
        MarketDataDependency("FORWARD_CURVE", underlying, ac, False,
                             "Equity forward curve"),
        MarketDataDependency("RISK_FREE_RATE", "", ac, False,
                             "Risk-free rate for cost-of-carry",
                             {"currency": pos.currency}),
        MarketDataDependency("HISTORICAL_PRICES", pos.instrument_id, ac, False,
                             "Historical price series for VaR",
                             {"lookbackDays": "252"}),
    ]


def _emit_government_bond(pos: PositionRisk) -> list[MarketDataDependency]:
    ac = pos.asset_class.value
    return [
        MarketDataDependency("YIELD_CURVE", "", ac, True,
                             "Sovereign yield curve for discounting",
                             {"curveId": pos.currency}),
        MarketDataDependency("HISTORICAL_PRICES", pos.instrument_id, ac, False,
                             "Historical bond prices for VaR",
                             {"lookbackDays": "252"}),
    ]


def _emit_corporate_bond(pos: PositionRisk) -> list[MarketDataDependency]:
    ac = pos.asset_class.value
    return [
        MarketDataDependency("YIELD_CURVE", "", ac, True,
                             "Risk-free yield curve for discounting",
                             {"curveId": pos.currency}),
        MarketDataDependency("CREDIT_SPREAD", pos.instrument_id, ac, True,
                             "Credit spread for issuer-specific risk pricing"),
        MarketDataDependency("HISTORICAL_PRICES", pos.instrument_id, ac, False,
                             "Historical bond prices for VaR",
                             {"lookbackDays": "252"}),
    ]


def _emit_fx_spot(pos: PositionRisk) -> list[MarketDataDependency]:
    ac = pos.asset_class.value
    return [
        MarketDataDependency("SPOT_PRICE", pos.instrument_id, ac, True,
                             "Current FX spot rate"),
        MarketDataDependency("HISTORICAL_PRICES", pos.instrument_id, ac, False,
                             "Historical FX rates for VaR",
                             {"lookbackDays": "252"}),
    ]


def _emit_fx_forward(pos: PositionRisk) -> list[MarketDataDependency]:
    ac = pos.asset_class.value
    base = pos.base_currency if isinstance(pos, FxPosition) else pos.currency
    quote = pos.quote_currency if isinstance(pos, FxPosition) else pos.currency
    return [
        MarketDataDependency("SPOT_PRICE", pos.instrument_id, ac, True,
                             "Current FX spot rate"),
        MarketDataDependency("YIELD_CURVE", "", ac, True,
                             "Base currency yield curve for forward pricing",
                             {"curveId": base}),
        MarketDataDependency("YIELD_CURVE", "", ac, True,
                             "Quote currency yield curve for forward pricing",
                             {"curveId": quote}),
        MarketDataDependency("FORWARD_CURVE", pos.instrument_id, ac, False,
                             "Explicit FX forward curve"),
        MarketDataDependency("HISTORICAL_PRICES", pos.instrument_id, ac, False,
                             "Historical FX rates for VaR",
                             {"lookbackDays": "252"}),
    ]


def _emit_fx_option(pos: PositionRisk) -> list[MarketDataDependency]:
    ac = pos.asset_class.value
    # For FX options the "underlying" is the currency pair instrument
    inst_id = pos.underlying_id if isinstance(pos, OptionPosition) else pos.instrument_id
    # Extract base/quote currencies from the underlying FX pair
    # FX options are OptionPositions — currency is the position currency (base)
    base = pos.currency
    # The quote currency is inferred; for now use the underlying_id convention
    # or a default. In practice the orchestrator knows both currencies.
    quote = "USD" if base != "USD" else "EUR"
    return [
        MarketDataDependency("SPOT_PRICE", inst_id, ac, True,
                             "Current FX spot rate"),
        MarketDataDependency("VOLATILITY_SURFACE", inst_id, ac, True,
                             "FX volatility surface for option pricing"),
        MarketDataDependency("YIELD_CURVE", "", ac, True,
                             "Base currency yield curve (foreign rate in Garman-Kohlhagen)",
                             {"curveId": base}),
        MarketDataDependency("YIELD_CURVE", "", ac, True,
                             "Quote currency yield curve (domestic rate in Garman-Kohlhagen)",
                             {"curveId": quote}),
        MarketDataDependency("HISTORICAL_PRICES", inst_id, ac, False,
                             "Historical FX rates for VaR",
                             {"lookbackDays": "252"}),
    ]


def _emit_commodity_future(pos: PositionRisk) -> list[MarketDataDependency]:
    underlying = pos.underlying_id if isinstance(pos, FuturePosition) else pos.instrument_id
    ac = pos.asset_class.value
    return [
        MarketDataDependency("FORWARD_CURVE", pos.instrument_id, ac, True,
                             "Commodity forward curve for futures pricing"),
        MarketDataDependency("SPOT_PRICE", underlying, ac, False,
                             "Commodity spot price"),
        MarketDataDependency("RISK_FREE_RATE", "", ac, False,
                             "Risk-free rate for cost-of-carry",
                             {"currency": pos.currency}),
        MarketDataDependency("HISTORICAL_PRICES", pos.instrument_id, ac, False,
                             "Historical futures prices for VaR",
                             {"lookbackDays": "252"}),
    ]


def _emit_commodity_option(pos: PositionRisk) -> list[MarketDataDependency]:
    underlying = pos.underlying_id if isinstance(pos, OptionPosition) else pos.instrument_id
    ac = pos.asset_class.value
    return [
        MarketDataDependency("FORWARD_CURVE", underlying, ac, True,
                             "Commodity forward curve for Black-76 pricing"),
        MarketDataDependency("VOLATILITY_SURFACE", underlying, ac, True,
                             "Commodity volatility surface for option pricing"),
        MarketDataDependency("RISK_FREE_RATE", "", ac, True,
                             "Risk-free rate for discounting in Black-76",
                             {"currency": pos.currency}),
        MarketDataDependency("SPOT_PRICE", underlying, ac, False,
                             "Commodity spot price"),
        MarketDataDependency("HISTORICAL_PRICES", pos.instrument_id, ac, False,
                             "Historical prices for VaR",
                             {"lookbackDays": "252"}),
    ]


def _emit_interest_rate_swap(pos: PositionRisk) -> list[MarketDataDependency]:
    return [
        MarketDataDependency("YIELD_CURVE", "", pos.asset_class.value, True,
                             "Yield curve for swap PV and DV01 computation",
                             {"curveId": pos.currency}),
    ]


_INSTRUMENT_TYPE_EMITTERS: dict[str, callable] = {
    "CASH_EQUITY": _emit_cash_equity,
    "EQUITY_OPTION": _emit_equity_option,
    "EQUITY_FUTURE": _emit_equity_future,
    "GOVERNMENT_BOND": _emit_government_bond,
    "CORPORATE_BOND": _emit_corporate_bond,
    "FX_SPOT": _emit_fx_spot,
    "FX_FORWARD": _emit_fx_forward,
    "FX_OPTION": _emit_fx_option,
    "COMMODITY_FUTURE": _emit_commodity_future,
    "COMMODITY_OPTION": _emit_commodity_option,
    "INTEREST_RATE_SWAP": _emit_interest_rate_swap,
}


def discover(positions: list[PositionRisk]) -> list[MarketDataDependency]:
    seen: set[tuple[str, str, frozenset]] = set()
    result: list[MarketDataDependency] = []

    for pos in positions:
        emitter = _INSTRUMENT_TYPE_EMITTERS.get(pos.instrument_type) if pos.instrument_type else None

        if emitter is not None:
            # Instrument-type-specific emission
            deps = emitter(pos)
            for dep in deps:
                key = (dep.data_type, dep.instrument_id, frozenset(dep.parameters.items()))
                if key in seen:
                    continue
                seen.add(key)
                result.append(dep)
        else:
            # Fallback to asset-class templates (backward compatible)
            templates = DEPENDENCIES_REGISTRY.get(pos.asset_class, [])
            for tmpl in templates:
                instrument_id = pos.instrument_id if tmpl.per_instrument else ""
                params = dict(tmpl.parameters)
                if tmpl.currency_parameter is not None:
                    params[tmpl.currency_parameter] = pos.currency
                key = (tmpl.data_type, instrument_id, frozenset(params.items()))
                if key in seen:
                    continue
                seen.add(key)
                result.append(MarketDataDependency(
                    data_type=tmpl.data_type,
                    instrument_id=instrument_id,
                    asset_class=pos.asset_class.value,
                    required=tmpl.required,
                    description=tmpl.description,
                    parameters=params,
                ))

    # Add portfolio-level correlation matrix if there are 2+ distinct asset classes.
    # Notes:
    #  - labels are the sorted asset-class names actually present in the portfolio.
    #    The correlation service is keyed by labels; omitting them caused the fetcher
    #    to skip the call (recorded MISSING) and blocked EOD promotion.
    #  - required=False because the engine consumes the matrix when present but
    #    falls back to internally-estimated correlations from historical price series
    #    when it isn't. Marking it required forced EOD promotion to fail whenever the
    #    correlation service didn't have an entry for the exact label set.
    asset_classes = {pos.asset_class for pos in positions}
    if len(asset_classes) >= 2:
        labels = ",".join(sorted(ac.value for ac in asset_classes))
        params = {"labels": labels}
        key = ("CORRELATION_MATRIX", "", frozenset(params.items()))
        if key not in seen:
            seen.add(key)
            result.append(MarketDataDependency(
                data_type="CORRELATION_MATRIX",
                instrument_id="",
                asset_class="",
                required=False,
                description="Cross-asset correlation matrix for portfolio diversification",
                parameters=params,
            ))

    return result
