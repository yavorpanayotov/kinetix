import time

from google.protobuf.timestamp_pb2 import Timestamp

from kinetix.common import types_pb2
from kinetix.risk import market_data_dependencies_pb2, regulatory_reporting_pb2, risk_calculation_pb2, stress_testing_pb2
from kinetix_risk.models import (
    AssetClass, BondPosition, CalculationType, ConfidenceLevel,
    CrossBookVaRResult, FrtbResult, FrtbRiskClass, FuturePosition, FxPosition,
    GreeksResult, OptionPosition, OptionType, PositionRisk,
    PositionStressImpact, StressScenario, StressTestResult,
    SwapPosition, VaRResult, ValuationResult,
)

_PROTO_ASSET_CLASS_TO_DOMAIN = {
    types_pb2.EQUITY: AssetClass.EQUITY,
    types_pb2.FIXED_INCOME: AssetClass.FIXED_INCOME,
    types_pb2.FX: AssetClass.FX,
    types_pb2.COMMODITY: AssetClass.COMMODITY,
    types_pb2.DERIVATIVE: AssetClass.DERIVATIVE,
}

_DOMAIN_ASSET_CLASS_TO_PROTO = {v: k for k, v in _PROTO_ASSET_CLASS_TO_DOMAIN.items()}

_PROTO_CONFIDENCE_TO_DOMAIN = {
    risk_calculation_pb2.CL_95: ConfidenceLevel.CL_95,
    risk_calculation_pb2.CL_975: ConfidenceLevel.CL_975,
    risk_calculation_pb2.CL_99: ConfidenceLevel.CL_99,
}

_PROTO_CALC_TYPE_TO_DOMAIN = {
    risk_calculation_pb2.HISTORICAL: CalculationType.HISTORICAL,
    risk_calculation_pb2.PARAMETRIC: CalculationType.PARAMETRIC,
    risk_calculation_pb2.MONTE_CARLO: CalculationType.MONTE_CARLO,
}


_OPTION_INSTRUMENT_TYPES = {
    types_pb2.EQUITY_OPTION,
    types_pb2.FX_OPTION,
    types_pb2.COMMODITY_OPTION,
}

_BOND_INSTRUMENT_TYPES = {
    types_pb2.GOVERNMENT_BOND,
    types_pb2.CORPORATE_BOND,
}

_FUTURE_INSTRUMENT_TYPES = {
    types_pb2.EQUITY_FUTURE,
    types_pb2.COMMODITY_FUTURE,
}

_FX_INSTRUMENT_TYPES = {
    types_pb2.FX_SPOT,
    types_pb2.FX_FORWARD,
}

_PROTO_INSTRUMENT_TYPE_TO_NAME: dict[int, str] = {
    types_pb2.CASH_EQUITY: "CASH_EQUITY",
    types_pb2.GOVERNMENT_BOND: "GOVERNMENT_BOND",
    types_pb2.CORPORATE_BOND: "CORPORATE_BOND",
    types_pb2.FX_SPOT: "FX_SPOT",
    types_pb2.FX_FORWARD: "FX_FORWARD",
    types_pb2.EQUITY_OPTION: "EQUITY_OPTION",
    types_pb2.EQUITY_FUTURE: "EQUITY_FUTURE",
    types_pb2.COMMODITY_FUTURE: "COMMODITY_FUTURE",
    types_pb2.COMMODITY_OPTION: "COMMODITY_OPTION",
    types_pb2.FX_OPTION: "FX_OPTION",
    types_pb2.INTEREST_RATE_SWAP: "INTEREST_RATE_SWAP",
}


def proto_positions_to_domain(proto_positions) -> list[PositionRisk]:
    result = []
    for p in proto_positions:
        asset_class = _PROTO_ASSET_CLASS_TO_DOMAIN.get(p.asset_class)
        if asset_class is None:
            continue
        market_value = float(p.market_value.amount) if p.market_value.amount else 0.0
        currency = p.market_value.currency or "USD"
        instrument_id = p.instrument_id.value

        inst_type = p.instrument_type
        instrument_type_name = _PROTO_INSTRUMENT_TYPE_TO_NAME.get(inst_type, "")

        if inst_type in _OPTION_INSTRUMENT_TYPES and p.HasField("option_attrs"):
            attrs = p.option_attrs
            option_type = OptionType.CALL if attrs.option_type == "CALL" else OptionType.PUT
            # Compute expiry_days from expiry_date if present; default 0 if expired or absent
            from datetime import date as _date
            expiry_days = 0
            if attrs.expiry_date:
                try:
                    expiry_days = max(0, (_date.fromisoformat(attrs.expiry_date) - _date.today()).days)
                except ValueError:
                    expiry_days = 0
            result.append(OptionPosition(
                instrument_id=instrument_id,
                underlying_id=attrs.underlying_id,
                option_type=option_type,
                strike=attrs.strike,
                expiry_days=expiry_days,
                spot_price=0.0,  # must be enriched with market data
                implied_vol=0.0,  # must be enriched with market data
                risk_free_rate=0.05,
                quantity=p.quantity,
                currency=currency,
                dividend_yield=attrs.dividend_yield,
                contract_multiplier=attrs.contract_multiplier if attrs.contract_multiplier > 0 else 1.0,
                asset_class=asset_class,
                instrument_type=instrument_type_name,
                market_value=market_value,
            ))
        elif inst_type in _BOND_INSTRUMENT_TYPES and p.HasField("bond_attrs"):
            attrs = p.bond_attrs
            result.append(BondPosition(
                instrument_id=instrument_id,
                asset_class=asset_class,
                market_value=market_value,
                currency=currency,
                instrument_type=instrument_type_name,
                face_value=attrs.face_value,
                coupon_rate=attrs.coupon_rate,
                coupon_frequency=attrs.coupon_frequency,
                maturity_date=attrs.maturity_date,
                issuer=attrs.issuer,
                credit_rating=attrs.credit_rating or "UNRATED",
                seniority=attrs.seniority or "SENIOR_UNSECURED",
                day_count_convention=attrs.day_count_convention or "ACT/ACT",
            ))
        elif inst_type in _FUTURE_INSTRUMENT_TYPES and p.HasField("future_attrs"):
            attrs = p.future_attrs
            result.append(FuturePosition(
                instrument_id=instrument_id,
                asset_class=asset_class,
                market_value=market_value,
                currency=currency,
                instrument_type=instrument_type_name,
                underlying_id=attrs.underlying_id,
                expiry_date=attrs.expiry_date,
                contract_size=attrs.contract_size if attrs.contract_size > 0 else 1.0,
            ))
        elif inst_type in _FX_INSTRUMENT_TYPES and p.HasField("fx_attrs"):
            attrs = p.fx_attrs
            result.append(FxPosition(
                instrument_id=instrument_id,
                asset_class=asset_class,
                market_value=market_value,
                currency=currency,
                instrument_type=instrument_type_name,
                base_currency=attrs.base_currency,
                quote_currency=attrs.quote_currency,
                delivery_date=attrs.delivery_date,
                forward_rate=attrs.forward_rate,
            ))
        elif inst_type == types_pb2.INTEREST_RATE_SWAP and p.HasField("swap_attrs"):
            attrs = p.swap_attrs
            result.append(SwapPosition(
                instrument_id=instrument_id,
                asset_class=asset_class,
                market_value=market_value,
                currency=currency,
                instrument_type=instrument_type_name,
                notional=attrs.notional,
                fixed_rate=attrs.fixed_rate,
                float_index=attrs.float_index,
                float_spread=attrs.float_spread,
                effective_date=attrs.effective_date,
                maturity_date=attrs.maturity_date,
                pay_receive=attrs.pay_receive or "PAY_FIXED",
                fixed_frequency=attrs.fixed_frequency if attrs.fixed_frequency > 0 else 2,
                float_frequency=attrs.float_frequency if attrs.float_frequency > 0 else 4,
                day_count_convention=attrs.day_count_convention or "ACT/360",
            ))
        else:
            # Fallback: plain PositionRisk for unspecified or unknown types
            result.append(PositionRisk(
                instrument_id=instrument_id,
                asset_class=asset_class,
                market_value=market_value,
                currency=currency,
                instrument_type=instrument_type_name,
            ))
    return result


def proto_confidence_to_domain(proto_cl) -> ConfidenceLevel:
    cl = _PROTO_CONFIDENCE_TO_DOMAIN.get(proto_cl)
    if cl is None:
        raise ValueError(f"Unknown confidence level: {proto_cl}")
    return cl


def proto_calculation_type_to_domain(proto_ct) -> CalculationType:
    ct = _PROTO_CALC_TYPE_TO_DOMAIN.get(proto_ct)
    if ct is None:
        raise ValueError(f"Unknown calculation type: {proto_ct}")
    return ct


def var_result_to_proto_response(
    result: VaRResult,
    book_id: str,
    calculation_type,
    confidence_level,
) -> risk_calculation_pb2.VaRResponse:
    breakdown = []
    for cb in result.component_breakdown:
        proto_ac = _DOMAIN_ASSET_CLASS_TO_PROTO[cb.asset_class]
        breakdown.append(risk_calculation_pb2.VaRComponentBreakdown(
            asset_class=proto_ac,
            var_contribution=cb.var_contribution,
            percentage_of_total=cb.percentage_of_total,
        ))

    now = Timestamp()
    now.FromSeconds(int(time.time()))

    return risk_calculation_pb2.VaRResponse(
        book_id=types_pb2.BookId(value=book_id),
        calculation_type=calculation_type,
        confidence_level=confidence_level,
        var_value=result.var_value,
        expected_shortfall=result.expected_shortfall,
        component_breakdown=breakdown,
        calculated_at=now,
    )


_VALUATION_OUTPUT_NAME_TO_PROTO = {
    "VAR": risk_calculation_pb2.VAR,
    "EXPECTED_SHORTFALL": risk_calculation_pb2.EXPECTED_SHORTFALL,
    "GREEKS": risk_calculation_pb2.GREEKS,
    "PV": risk_calculation_pb2.PV,
}

_PROTO_VALUATION_OUTPUT_TO_NAME = {v: k for k, v in _VALUATION_OUTPUT_NAME_TO_PROTO.items()}


def proto_valuation_outputs_to_names(proto_outputs) -> list[str]:
    return [
        _PROTO_VALUATION_OUTPUT_TO_NAME.get(o, "UNKNOWN")
        for o in proto_outputs
        if o != risk_calculation_pb2.VALUATION_OUTPUT_UNSPECIFIED
    ]


def _greeks_result_to_summary(result: GreeksResult) -> risk_calculation_pb2.GreeksSummary:
    asset_class_greeks = []
    for ac in sorted(result.delta.keys(), key=lambda a: a.value):
        proto_ac = _DOMAIN_ASSET_CLASS_TO_PROTO[ac]
        asset_class_greeks.append(risk_calculation_pb2.GreekValues(
            asset_class=proto_ac,
            delta=result.delta[ac],
            gamma=result.gamma[ac],
            vega=result.vega[ac],
        ))
    return risk_calculation_pb2.GreeksSummary(
        asset_class_greeks=asset_class_greeks,
        theta=result.theta,
        rho=result.rho,
    )


def valuation_result_to_proto_response(
    result: ValuationResult,
    book_id: str,
    calculation_type,
    confidence_level,
    model_version: str = "",
    monte_carlo_seed: int = 0,
) -> risk_calculation_pb2.ValuationResponse:
    now = Timestamp()
    now.FromSeconds(int(time.time()))

    breakdown = []
    var_value = 0.0
    expected_shortfall = 0.0

    if result.var_result is not None:
        var_value = result.var_result.var_value
        expected_shortfall = result.var_result.expected_shortfall
        for cb in result.var_result.component_breakdown:
            proto_ac = _DOMAIN_ASSET_CLASS_TO_PROTO[cb.asset_class]
            breakdown.append(risk_calculation_pb2.VaRComponentBreakdown(
                asset_class=proto_ac,
                var_contribution=cb.var_contribution,
                percentage_of_total=cb.percentage_of_total,
            ))

    greeks_summary = None
    if result.greeks_result is not None:
        greeks_summary = _greeks_result_to_summary(result.greeks_result)

    computed_proto = [
        _VALUATION_OUTPUT_NAME_TO_PROTO[o]
        for o in result.computed_outputs
        if o in _VALUATION_OUTPUT_NAME_TO_PROTO
    ]

    position_greeks_proto = []
    if result.position_greeks:
        for inst_id, greeks in result.position_greeks.items():
            position_greeks_proto.append(risk_calculation_pb2.PositionGreek(
                instrument_id=inst_id,
                delta=greeks.get("delta", 0.0),
                gamma=greeks.get("gamma", 0.0),
                vega=greeks.get("vega", 0.0),
                theta=greeks.get("theta", 0.0),
                rho=greeks.get("rho", 0.0),
            ))

    response = risk_calculation_pb2.ValuationResponse(
        book_id=types_pb2.BookId(value=book_id),
        calculation_type=calculation_type,
        confidence_level=confidence_level,
        var_value=var_value,
        expected_shortfall=expected_shortfall,
        component_breakdown=breakdown,
        calculated_at=now,
        computed_outputs=computed_proto,
        pv_value=result.pv_value or 0.0,
        model_version=model_version,
        monte_carlo_seed=monte_carlo_seed,
        position_greeks=position_greeks_proto,
        degradation_flags=list(result.degradation_flags) if result.degradation_flags else [],
    )
    if greeks_summary is not None:
        response.greeks.CopyFrom(greeks_summary)

    return response


def cross_book_var_result_to_proto_response(
    result: CrossBookVaRResult,
    book_ids: list[str],
    portfolio_group_id: str,
    calculation_type,
    confidence_level,
    model_version: str = "",
    monte_carlo_seed: int = 0,
) -> risk_calculation_pb2.CrossBookVaRResponse:
    now = Timestamp()
    now.FromSeconds(int(time.time()))

    breakdown = []
    if result.var_result is not None:
        for cb in result.var_result.component_breakdown:
            proto_ac = _DOMAIN_ASSET_CLASS_TO_PROTO[cb.asset_class]
            breakdown.append(risk_calculation_pb2.VaRComponentBreakdown(
                asset_class=proto_ac,
                var_contribution=cb.var_contribution,
                percentage_of_total=cb.percentage_of_total,
            ))

    book_contributions = []
    for bc in result.book_contributions:
        book_contributions.append(risk_calculation_pb2.BookVaRContribution(
            book_id=types_pb2.BookId(value=bc.book_id),
            var_contribution=bc.var_contribution,
            percentage_of_total=bc.percentage_of_total,
            standalone_var=bc.standalone_var,
            diversification_benefit=bc.diversification_benefit,
            marginal_var=bc.marginal_var,
            incremental_var=bc.incremental_var,
        ))

    return risk_calculation_pb2.CrossBookVaRResponse(
        portfolio_group_id=portfolio_group_id,
        book_ids=[types_pb2.BookId(value=bid) for bid in book_ids],
        calculation_type=calculation_type,
        confidence_level=confidence_level,
        var_value=result.var_result.var_value,
        expected_shortfall=result.var_result.expected_shortfall,
        component_breakdown=breakdown,
        book_contributions=book_contributions,
        total_standalone_var=result.total_standalone_var,
        diversification_benefit=result.diversification_benefit,
        calculated_at=now,
        model_version=model_version,
        monte_carlo_seed=monte_carlo_seed,
    )


_ASSET_CLASS_NAME_TO_DOMAIN = {ac.value: ac for ac in AssetClass}


def proto_stress_request_to_scenario(request) -> StressScenario:
    vol_shocks = {}
    for ac_name, shock in request.vol_shocks.items():
        ac = _ASSET_CLASS_NAME_TO_DOMAIN.get(ac_name)
        if ac:
            vol_shocks[ac] = shock

    price_shocks = {}
    for ac_name, shock in request.price_shocks.items():
        ac = _ASSET_CLASS_NAME_TO_DOMAIN.get(ac_name)
        if ac:
            price_shocks[ac] = shock

    return StressScenario(
        name=request.scenario_name or "hypothetical",
        description=request.description or "",
        vol_shocks=vol_shocks,
        correlation_override=None,
        price_shocks=price_shocks,
    )


def position_stress_impact_to_proto(
    impact: PositionStressImpact,
) -> stress_testing_pb2.PositionStressImpact:
    return stress_testing_pb2.PositionStressImpact(
        instrument_id=impact.instrument_id,
        asset_class=_DOMAIN_ASSET_CLASS_TO_PROTO[impact.asset_class],
        base_market_value=impact.base_market_value,
        stressed_market_value=impact.stressed_market_value,
        pnl_impact=impact.pnl_impact,
        percentage_of_total=impact.percentage_of_total,
    )


def stress_result_to_proto(result: StressTestResult) -> stress_testing_pb2.StressTestResponse:
    impacts = []
    for impact in result.asset_class_impacts:
        proto_ac = _DOMAIN_ASSET_CLASS_TO_PROTO[impact.asset_class]
        impacts.append(stress_testing_pb2.AssetClassImpact(
            asset_class=proto_ac,
            base_exposure=impact.base_exposure,
            stressed_exposure=impact.stressed_exposure,
            pnl_impact=impact.pnl_impact,
        ))

    position_impacts = [
        position_stress_impact_to_proto(pi)
        for pi in result.position_impacts
    ]

    now = Timestamp()
    now.FromSeconds(int(time.time()))

    return stress_testing_pb2.StressTestResponse(
        scenario_name=result.scenario_name,
        base_var=result.base_var,
        stressed_var=result.stressed_var,
        pnl_impact=result.pnl_impact,
        asset_class_impacts=impacts,
        calculated_at=now,
        position_impacts=position_impacts,
    )


def greeks_result_to_proto(result: GreeksResult) -> stress_testing_pb2.GreeksResponse:
    asset_class_greeks = []
    for ac in sorted(result.delta.keys(), key=lambda a: a.value):
        proto_ac = _DOMAIN_ASSET_CLASS_TO_PROTO[ac]
        asset_class_greeks.append(stress_testing_pb2.StressGreekValues(
            asset_class=proto_ac,
            delta=result.delta[ac],
            gamma=result.gamma[ac],
            vega=result.vega[ac],
        ))

    now = Timestamp()
    now.FromSeconds(int(time.time()))

    return stress_testing_pb2.GreeksResponse(
        book_id=result.book_id,
        asset_class_greeks=asset_class_greeks,
        theta=result.theta,
        rho=result.rho,
        calculated_at=now,
    )


_DOMAIN_FRTB_RC_TO_PROTO = {
    FrtbRiskClass.GIRR: regulatory_reporting_pb2.GIRR,
    FrtbRiskClass.CSR_NON_SEC: regulatory_reporting_pb2.CSR_NON_SEC,
    FrtbRiskClass.CSR_SEC_CTP: regulatory_reporting_pb2.CSR_SEC_CTP,
    FrtbRiskClass.CSR_SEC_NON_CTP: regulatory_reporting_pb2.CSR_SEC_NON_CTP,
    FrtbRiskClass.EQUITY: regulatory_reporting_pb2.FRTB_EQUITY,
    FrtbRiskClass.COMMODITY: regulatory_reporting_pb2.FRTB_COMMODITY,
    FrtbRiskClass.FX: regulatory_reporting_pb2.FRTB_FX,
}


def frtb_result_to_proto(result: FrtbResult) -> regulatory_reporting_pb2.FrtbResponse:
    rc_charges = []
    for rcc in result.sbm.risk_class_charges:
        # Populate per-tenor detail for GIRR charges (BCBS 352 tenor attribution)
        tenor_charges_proto = []
        if hasattr(rcc, "tenor_charges") and rcc.tenor_charges:
            for tc in rcc.tenor_charges:
                tenor_charges_proto.append(regulatory_reporting_pb2.TenorCharge(
                    tenor_label=tc.tenor_label,
                    sensitivity=tc.sensitivity,
                    risk_weight=tc.risk_weight,
                    weighted_sensitivity=tc.weighted_sensitivity,
                ))
        rc_charges.append(regulatory_reporting_pb2.RiskClassCharge(
            risk_class=_DOMAIN_FRTB_RC_TO_PROTO[rcc.risk_class],
            delta_charge=rcc.delta_charge,
            vega_charge=rcc.vega_charge,
            curvature_charge=rcc.curvature_charge,
            total_charge=rcc.total_charge,
            tenor_charges=tenor_charges_proto,
        ))

    now = Timestamp()
    now.FromSeconds(int(time.time()))

    return regulatory_reporting_pb2.FrtbResponse(
        book_id=result.book_id,
        sbm=regulatory_reporting_pb2.SbmResult(
            risk_class_charges=rc_charges,
            total_sbm_charge=result.sbm.total_sbm_charge,
        ),
        drc=regulatory_reporting_pb2.DrcResult(
            gross_jtd=result.drc.gross_jtd,
            hedge_benefit=result.drc.hedge_benefit,
            net_drc=result.drc.net_drc,
        ),
        rrao=regulatory_reporting_pb2.RraoResult(
            exotic_notional=result.rrao.exotic_notional,
            other_notional=result.rrao.other_notional,
            total_rrao=result.rrao.total_rrao,
        ),
        total_capital_charge=result.total_capital_charge,
        calculated_at=now,
    )


def report_to_proto(
    book_id: str,
    fmt: int,
    content: str,
) -> regulatory_reporting_pb2.GenerateReportResponse:
    now = Timestamp()
    now.FromSeconds(int(time.time()))

    return regulatory_reporting_pb2.GenerateReportResponse(
        book_id=book_id,
        format=fmt,
        content=content,
        generated_at=now,
    )


_PROTO_MARKET_DATA_TYPE_TO_NAME = {
    risk_calculation_pb2.SPOT_PRICE: "SPOT_PRICE",
    risk_calculation_pb2.HISTORICAL_PRICES: "HISTORICAL_PRICES",
    risk_calculation_pb2.VOLATILITY_SURFACE: "VOLATILITY_SURFACE",
    risk_calculation_pb2.YIELD_CURVE: "YIELD_CURVE",
    risk_calculation_pb2.RISK_FREE_RATE: "RISK_FREE_RATE",
    risk_calculation_pb2.DIVIDEND_YIELD: "DIVIDEND_YIELD",
    risk_calculation_pb2.CREDIT_SPREAD: "CREDIT_SPREAD",
    risk_calculation_pb2.FORWARD_CURVE: "FORWARD_CURVE",
    risk_calculation_pb2.CORRELATION_MATRIX: "CORRELATION_MATRIX",
}


def proto_market_data_to_domain(market_data_list) -> list[dict]:
    result = []
    for md in market_data_list:
        data_type_name = _PROTO_MARKET_DATA_TYPE_TO_NAME.get(
            md.data_type, "UNKNOWN"
        )
        item = {
            "data_type": data_type_name,
            "instrument_id": md.instrument_id,
            "asset_class": md.asset_class,
        }
        value_field = md.WhichOneof("value")
        if value_field == "scalar":
            item["scalar"] = md.scalar
        elif value_field == "time_series":
            item["time_series"] = [
                {
                    "timestamp_seconds": pt.timestamp.seconds,
                    "value": pt.value,
                }
                for pt in md.time_series.points
            ]
        elif value_field == "matrix":
            item["matrix"] = {
                "rows": md.matrix.rows,
                "cols": md.matrix.cols,
                "values": list(md.matrix.values),
                "labels": list(md.matrix.labels),
            }
        elif value_field == "curve":
            item["curve"] = [
                {"tenor": pt.tenor, "value": pt.value}
                for pt in md.curve.points
            ]
        result.append(item)
    return result


_MARKET_DATA_TYPE_NAME_TO_PROTO = {
    "SPOT_PRICE": risk_calculation_pb2.SPOT_PRICE,
    "HISTORICAL_PRICES": risk_calculation_pb2.HISTORICAL_PRICES,
    "VOLATILITY_SURFACE": risk_calculation_pb2.VOLATILITY_SURFACE,
    "YIELD_CURVE": risk_calculation_pb2.YIELD_CURVE,
    "RISK_FREE_RATE": risk_calculation_pb2.RISK_FREE_RATE,
    "DIVIDEND_YIELD": risk_calculation_pb2.DIVIDEND_YIELD,
    "CREDIT_SPREAD": risk_calculation_pb2.CREDIT_SPREAD,
    "FORWARD_CURVE": risk_calculation_pb2.FORWARD_CURVE,
    "CORRELATION_MATRIX": risk_calculation_pb2.CORRELATION_MATRIX,
}


def dependencies_to_proto(
    dependencies: list,
) -> market_data_dependencies_pb2.DataDependenciesResponse:
    proto_deps = []
    for dep in dependencies:
        proto_data_type = _MARKET_DATA_TYPE_NAME_TO_PROTO.get(
            dep.data_type,
            risk_calculation_pb2.MARKET_DATA_TYPE_UNSPECIFIED,
        )
        proto_deps.append(market_data_dependencies_pb2.MarketDataDependency(
            data_type=proto_data_type,
            instrument_id=dep.instrument_id,
            asset_class=dep.asset_class,
            required=dep.required,
            description=dep.description,
            parameters=dep.parameters,
        ))
    return market_data_dependencies_pb2.DataDependenciesResponse(
        dependencies=proto_deps,
    )
