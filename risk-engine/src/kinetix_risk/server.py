import logging
import os
import signal
from concurrent import futures
from pathlib import Path

import grpc
import prometheus_client

logger = logging.getLogger(__name__)

from kinetix.risk import attribution_pb2_grpc, counterparty_risk_pb2_grpc, liquidity_pb2_grpc, market_data_dependencies_pb2_grpc, ml_prediction_pb2_grpc, regulatory_reporting_pb2_grpc, risk_calculation_pb2_grpc, stress_testing_pb2_grpc
from kinetix_risk.sa_ccr_server import SaCcrServicer
from kinetix_risk.converters import (
    cross_book_var_result_to_proto_response,
    proto_calculation_type_to_domain,
    proto_confidence_to_domain,
    proto_market_data_to_domain,
    proto_positions_to_domain,
    proto_valuation_outputs_to_names,
    valuation_result_to_proto_response,
    var_result_to_proto_response,
)
from kinetix_risk.version import get_model_version
from kinetix_risk.key_rate_duration import STANDARD_TENOR_BUCKETS, calculate_krd
from kinetix_risk.market_data_consumer import consume_market_data
from kinetix_risk.market_data_models import YieldCurveData
from datetime import date as _date, timedelta as _timedelta

from kinetix_risk.black_scholes import bs_greeks
from kinetix_risk.bond_pricing import bond_dv01
from kinetix_risk.greeks import (
    PRICE_BUMP,
    RATE_BUMP,
    VOL_BUMP,
    calculate_greeks,
)
from kinetix_risk.log_formatter import JsonLogFormatter
from kinetix_risk.pnl_attribution import (
    MarketMove,
    aggregate_book_greeks,
    decompose_greek_pnl,
)
from kinetix_risk.pnl_attribution_metrics import record_greek_pnl_attribution
from kinetix_risk.models import AssetClass, BondPosition, OptionPosition, OptionType


class OptionPricingConvergenceError(RuntimeError):
    """Raised when an option's pricing model fails to converge.

    Per the Gap 8 demo anomaly contract (docs/plans/demo-review.md), the
    option model deliberately reports non-convergence for instruments listed
    in NONCONVERGENT_OPTION_INSTRUMENT_IDS. The CalculatePricingGreeks
    handler catches this, logs WARN with data_quality_intent=
    intentional_anomaly_demo, and skips the result so consumers see the
    instrument as Greeks-unavailable (UI renders N/A) rather than 0.
    """


# Comma-separated list of option instrument IDs whose pricing model is
# deliberately non-convergent for the demo. Defaults to one position
# (AAPL-P-180-20260620) so the demo grid shows exactly one N/A row.
NONCONVERGENT_OPTION_INSTRUMENT_IDS: frozenset[str] = frozenset(
    s.strip() for s in os.environ.get(
        "DEMO_NONCONVERGENT_OPTIONS",
        "AAPL-P-180-20260620",
    ).split(",") if s.strip()
)


def _maturity_iso_date(maturity_years: float) -> str:
    """BondPosition.maturity_date is ISO-formatted (`bond_pricing._years_to_maturity`
    parses via `date.fromisoformat`); convert the wire-format `maturity_years` to an
    actual future date so DV01 reflects the requested tenor rather than collapsing to
    expired-bond face value."""
    return (_date.today() + _timedelta(days=int(maturity_years * 365.25))).isoformat()
from kinetix_risk.metrics import (
    cross_book_diversification_benefit,
    greeks_delta,
    greeks_gamma,
    greeks_rho,
    greeks_theta,
    greeks_vega,
    risk_var_component_contribution,
    risk_var_expected_shortfall,
    risk_var_value,
)
from kinetix_risk.otel_init import get_tracer
from kinetix_risk.ml.model_store import ModelStore
from kinetix_risk.ml_server import MLPredictionServicer
from kinetix_risk.cross_book_var import calculate_cross_book_var
from kinetix_risk.portfolio_risk import calculate_book_var
from kinetix_risk.valuation import calculate_valuation
from kinetix_risk.volatility import VolatilityProvider
from kinetix_risk.dependencies_server import MarketDataDependenciesServicer
from kinetix_risk.attribution_server import AttributionServicer
from kinetix_risk.counterparty_risk_server import CounterpartyRiskServicer
from kinetix_risk.liquidity_server import LiquidityAdjustedVaRServicer
from kinetix_risk.regulatory_server import RegulatoryReportingServicer
from kinetix_risk.stress_server import StressTestServicer
from kinetix_risk.factor_server import FactorDecompositionServicer
from kinetix_risk.hedge_optimizer import HedgeOptimizerServicer


class RiskCalculationServicer(risk_calculation_pb2_grpc.RiskCalculationServiceServicer):

    def CalculateVaR(self, request, context):
        book_id = request.book_id.value
        tracer = get_tracer()
        with tracer.start_as_current_span("CalculateVaR") as span:
            span.set_attribute("book_id", book_id)
            return self._calculate_var_impl(request, context, span)

    def _calculate_var_impl(self, request, context, span=None):
        try:
            positions = proto_positions_to_domain(request.positions)
            calc_type = proto_calculation_type_to_domain(request.calculation_type)
            confidence = proto_confidence_to_domain(request.confidence_level)

            calculation_type = (
                calc_type.value if hasattr(calc_type, "value") else str(calc_type)
            )
            if span is not None:
                span.set_attribute("calculation_type", calculation_type)
            logger.info(
                "Starting VaR calculation",
                extra={
                    "book_id": request.book_id.value,
                    "correlation_id": None,
                    "calculation_type": calculation_type,
                },
            )

            market_data_dicts = proto_market_data_to_domain(request.market_data)
            bundle = consume_market_data(market_data_dicts)

            result = calculate_book_var(
                positions=positions,
                calculation_type=calc_type,
                confidence_level=confidence,
                time_horizon_days=request.time_horizon_days or 1,
                num_simulations=request.num_simulations or 10_000,
                volatility_provider=bundle.volatility_provider or VolatilityProvider.static(),
                correlation_matrix=bundle.correlation_matrix,
            )

            book_id = request.book_id.value
            ct = calc_type.value if hasattr(calc_type, "value") else str(calc_type)
            cl = confidence.name
            risk_var_value.labels(book_id=book_id, calculation_type=ct, confidence_level=cl).set(result.var_value)
            risk_var_expected_shortfall.labels(book_id=book_id, calculation_type=ct, confidence_level=cl).set(result.expected_shortfall)
            for component in result.component_breakdown:
                risk_var_component_contribution.labels(
                    book_id=book_id,
                    asset_class=component.asset_class.value,
                ).set(component.var_contribution)

            # Calculate and publish Greeks alongside VaR so the Greeks
            # dashboard stays current regardless of which RPC path is used.
            try:
                gr = calculate_greeks(
                    positions=positions,
                    calculation_type=calc_type,
                    confidence_level=confidence,
                    time_horizon_days=request.time_horizon_days or 1,
                    book_id=book_id,
                    base_var_value=result.var_value,
                )
                for ac, val in gr.delta.items():
                    greeks_delta.labels(book_id=book_id, asset_class=ac.value).set(val)
                for ac, val in gr.gamma.items():
                    greeks_gamma.labels(book_id=book_id, asset_class=ac.value).set(val)
                for ac, val in gr.vega.items():
                    greeks_vega.labels(book_id=book_id, asset_class=ac.value).set(val)
                greeks_theta.labels(book_id=book_id).set(gr.theta)
                greeks_rho.labels(book_id=book_id).set(gr.rho)

                # Greek-component P&L attribution: decompose the book's P&L
                # against the standardised 1-day market move the VaR engine
                # uses to derive its Greeks, so the desk can see whether the
                # move is delta-, gamma-, vega- or theta-driven.
                book_spot = sum(abs(p.market_value) for p in positions)
                book_greeks = aggregate_book_greeks(
                    delta_by_asset_class=gr.delta,
                    gamma_by_asset_class=gr.gamma,
                    vega_by_asset_class=gr.vega,
                    theta=gr.theta,
                    rho=gr.rho,
                    spot=book_spot,
                )
                standard_move = MarketMove(
                    price_change=PRICE_BUMP,
                    vol_change=VOL_BUMP,
                    time_change_days=1.0,
                    rate_change=RATE_BUMP,
                )
                record_greek_pnl_attribution(
                    decompose_greek_pnl(book_greeks, standard_move),
                    book_id=book_id,
                )
            except Exception:
                logger.warning("Greeks calculation failed in CalculateVaR for book %s", book_id, exc_info=True)

            logger.info(
                "Completed VaR calculation",
                extra={
                    "book_id": book_id,
                    "correlation_id": None,
                    "calculation_type": ct,
                },
            )

            return var_result_to_proto_response(
                result,
                book_id=request.book_id.value,
                calculation_type=request.calculation_type,
                confidence_level=request.confidence_level,
            )
        except ValueError as e:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(e))
        except Exception as e:
            logger.exception("CalculateVaR failed")
            context.abort(grpc.StatusCode.INTERNAL, str(e))

    def Valuate(self, request, context):
        book_id = request.book_id.value
        tracer = get_tracer()
        with tracer.start_as_current_span("Valuate") as span:
            span.set_attribute("book_id", book_id)
            try:
                positions = proto_positions_to_domain(request.positions)
                calc_type = proto_calculation_type_to_domain(request.calculation_type)
                confidence = proto_confidence_to_domain(request.confidence_level)

                market_data_dicts = proto_market_data_to_domain(request.market_data)
                bundle = consume_market_data(market_data_dicts)

                requested_outputs = proto_valuation_outputs_to_names(request.requested_outputs)

                # A seed of 0 means unseeded (non-deterministic); >0 means deterministic
                seed = request.monte_carlo_seed if request.monte_carlo_seed > 0 else None

                ct = calc_type.value if hasattr(calc_type, "value") else str(calc_type)
                span.set_attribute("calculation_type", ct)
                span.set_attribute("num_simulations", request.num_simulations or 10_000)

                result = calculate_valuation(
                    positions=positions,
                    calculation_type=calc_type,
                    confidence_level=confidence,
                    time_horizon_days=request.time_horizon_days or 1,
                    num_simulations=request.num_simulations or 10_000,
                    volatility_provider=bundle.volatility_provider or VolatilityProvider.static(),
                    correlation_matrix=bundle.correlation_matrix,
                    requested_outputs=requested_outputs,
                    book_id=book_id,
                    seed=seed,
                    market_data_bundle=bundle,
                )

                cl = confidence.name

                if result.var_result is not None:
                    risk_var_value.labels(book_id=book_id, calculation_type=ct, confidence_level=cl).set(result.var_result.var_value)
                    risk_var_expected_shortfall.labels(book_id=book_id, calculation_type=ct, confidence_level=cl).set(result.var_result.expected_shortfall)
                    for component in result.var_result.component_breakdown:
                        risk_var_component_contribution.labels(
                            book_id=book_id,
                            asset_class=component.asset_class.value,
                        ).set(component.var_contribution)
                    span.set_attribute("var_value", result.var_result.var_value)

                if result.greeks_result is not None:
                    gr = result.greeks_result
                    for asset_class, val in gr.delta.items():
                        greeks_delta.labels(book_id=book_id, asset_class=asset_class.value).set(val)
                    for asset_class, val in gr.gamma.items():
                        greeks_gamma.labels(book_id=book_id, asset_class=asset_class.value).set(val)
                    for asset_class, val in gr.vega.items():
                        greeks_vega.labels(book_id=book_id, asset_class=asset_class.value).set(val)
                    greeks_theta.labels(book_id=book_id).set(gr.theta)
                    greeks_rho.labels(book_id=book_id).set(gr.rho)

                return valuation_result_to_proto_response(
                    result,
                    book_id=request.book_id.value,
                    calculation_type=request.calculation_type,
                    confidence_level=request.confidence_level,
                    model_version=get_model_version(),
                    monte_carlo_seed=request.monte_carlo_seed,
                )
            except ValueError as e:
                context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(e))
            except Exception as e:
                logger.exception("Valuate failed")
                context.abort(grpc.StatusCode.INTERNAL, str(e))

    def CalculateCrossBookVaR(self, request, context):
        try:
            positions = proto_positions_to_domain(request.positions)
            calc_type = proto_calculation_type_to_domain(request.calculation_type)
            confidence = proto_confidence_to_domain(request.confidence_level)

            market_data_dicts = proto_market_data_to_domain(request.market_data)
            bundle = consume_market_data(market_data_dicts)

            # Group positions by book_id
            book_ids = [bid.value for bid in request.book_ids]
            books: dict[str, list] = {bid: [] for bid in book_ids}
            for pos, proto_pos in zip(positions, request.positions):
                bid = proto_pos.book_id.value if proto_pos.book_id else ""
                if bid in books:
                    books[bid].append(pos)
                else:
                    books.setdefault(bid, []).append(pos)

            seed = request.monte_carlo_seed if request.monte_carlo_seed > 0 else None

            result = calculate_cross_book_var(
                books=books,
                calculation_type=calc_type,
                confidence_level=confidence,
                time_horizon_days=request.time_horizon_days or 1,
                num_simulations=request.num_simulations or 10_000,
                volatility_provider=bundle.volatility_provider or VolatilityProvider.static(),
                correlation_matrix=bundle.correlation_matrix,
                seed=seed,
            )

            ct = calc_type.value if hasattr(calc_type, "value") else str(calc_type)
            cl = confidence.name
            for contrib in result.book_contributions:
                risk_var_value.labels(
                    book_id=contrib.book_id, calculation_type=ct, confidence_level=cl,
                ).set(contrib.standalone_var)
            cross_book_diversification_benefit.labels(
                portfolio_group_id=request.portfolio_group_id or "default",
            ).set(result.diversification_benefit)

            return cross_book_var_result_to_proto_response(
                result,
                book_ids=book_ids,
                portfolio_group_id=request.portfolio_group_id,
                calculation_type=request.calculation_type,
                confidence_level=request.confidence_level,
                model_version=get_model_version(),
                monte_carlo_seed=request.monte_carlo_seed,
            )
        except ValueError as e:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(e))
        except Exception as e:
            logger.exception("CalculateCrossBookVaR failed")
            context.abort(grpc.StatusCode.INTERNAL, str(e))

    def CalculateVaRStream(self, request_iterator, context):
        for request in request_iterator:
            try:
                yield self.CalculateVaR(request, context)
            except Exception as e:
                logger.exception("CalculateVaRStream failed for request")
                context.abort(grpc.StatusCode.INTERNAL, str(e))

    def DecomposeFactorRisk(self, request, context):
        tracer = get_tracer()
        with tracer.start_as_current_span("DecomposeFactorRisk") as span:
            book_id = request.book_id.value if request.book_id else ""
            span.set_attribute("book_id", book_id)
            return FactorDecompositionServicer().DecomposeFactorRisk(request, context)

    def SuggestHedge(self, request, context):
        return HedgeOptimizerServicer().SuggestHedge(request, context)

    def CalculatePricingGreeks(self, request, context):
        """Returns analytical closed-form pricing Greeks per instrument.

        For each input:
        - asset_class == "OPTION": Black-Scholes Greeks via bs_greeks()
        - asset_class == "BOND" or "SWAP": DV01 via bond_dv01()
        - asset_class == "EQUITY" or "FX": identity delta = 1.0, others zero
        - anything else: skipped (no row in response)

        Errors during a single instrument's computation are logged and the
        instrument is skipped rather than failing the whole batch — SOD job
        consumers tolerate partial population by falling back to VaR Greeks
        for missing instruments.
        """
        from kinetix.risk import risk_calculation_pb2

        results = []
        for inp in request.instruments:
            try:
                ac = (inp.asset_class or "").upper()
                if ac == "OPTION":
                    if inp.instrument_id in NONCONVERGENT_OPTION_INSTRUMENT_IDS:
                        raise OptionPricingConvergenceError(
                            f"convergence failure for {inp.instrument_id}"
                        )
                    option = OptionPosition(
                        instrument_id=inp.instrument_id,
                        underlying_id=inp.instrument_id,
                        option_type=OptionType(inp.option_type.upper() or "CALL"),
                        strike=float(inp.strike),
                        expiry_days=int(inp.expiry_days),
                        spot_price=float(inp.spot_price),
                        implied_vol=float(inp.implied_vol),
                        risk_free_rate=float(inp.risk_free_rate),
                        dividend_yield=float(inp.dividend_yield),
                    )
                    g = bs_greeks(option)
                    results.append(risk_calculation_pb2.PricingGreekInstrumentResult(
                        instrument_id=inp.instrument_id,
                        delta=float(g["delta"]),
                        gamma=float(g["gamma"]),
                        vega=float(g["vega"]),
                        theta=float(g["theta"]),
                        rho=float(g["rho"]),
                        vanna=float(g["vanna"]),
                        volga=float(g["volga"]),
                        charm=float(g["charm"]),
                    ))
                elif ac in ("BOND", "FIXED_INCOME"):
                    bond = BondPosition(
                        instrument_id=inp.instrument_id,
                        asset_class=AssetClass.FIXED_INCOME,
                        market_value=0.0,
                        currency="USD",
                        face_value=float(inp.face_value),
                        coupon_rate=float(inp.coupon_rate),
                        coupon_frequency=int(inp.coupon_frequency or 2),
                        maturity_date=_maturity_iso_date(float(inp.maturity_years)),
                    )
                    dv01 = bond_dv01(bond, float(inp.yield_rate))
                    results.append(risk_calculation_pb2.PricingGreekInstrumentResult(
                        instrument_id=inp.instrument_id,
                        bond_dv01=float(dv01),
                    ))
                elif ac == "SWAP":
                    # Treat swap DV01 as bond DV01 against the fixed leg —
                    # adequate for the linear-instrument Taylor expansion.
                    bond = BondPosition(
                        instrument_id=inp.instrument_id,
                        asset_class=AssetClass.FIXED_INCOME,
                        market_value=0.0,
                        currency="USD",
                        face_value=float(inp.face_value),
                        coupon_rate=float(inp.coupon_rate),
                        coupon_frequency=int(inp.coupon_frequency or 2),
                        maturity_date=_maturity_iso_date(float(inp.maturity_years)),
                    )
                    dv01 = bond_dv01(bond, float(inp.yield_rate))
                    results.append(risk_calculation_pb2.PricingGreekInstrumentResult(
                        instrument_id=inp.instrument_id,
                        swap_dv01=float(dv01),
                    ))
                elif ac in ("EQUITY", "FX"):
                    # Linear instrument: delta = 1, all other Greeks = 0.
                    results.append(risk_calculation_pb2.PricingGreekInstrumentResult(
                        instrument_id=inp.instrument_id,
                        delta=1.0,
                    ))
                else:
                    logger.debug("CalculatePricingGreeks: skipping unknown asset_class %r for %s", ac, inp.instrument_id)
            except OptionPricingConvergenceError as e:
                logger.warning(
                    "CalculatePricingGreeks: convergence failure for %s (data_quality_intent=intentional_anomaly_demo); skipping — Greeks render as N/A: %s",
                    inp.instrument_id,
                    e,
                )
                continue
            except Exception:
                logger.exception("CalculatePricingGreeks: failed for instrument %s; skipping", inp.instrument_id)
                continue

        return risk_calculation_pb2.PricingGreeksResponse(results=results)

    def CalculateKeyRateDurations(self, request, context):
        try:
            tenors = [
                (int(pt.tenor), pt.value)
                for pt in request.yield_curve.points
                if pt.tenor.isdigit()
            ]
            if not tenors:
                context.abort(grpc.StatusCode.INVALID_ARGUMENT, "yield_curve must have at least one point")
                return

            yield_curve = YieldCurveData(tenors=tenors)
            result = calculate_krd(
                face_value=float(request.face_value),
                coupon_rate=float(request.coupon_rate),
                coupon_frequency=request.coupon_frequency or 2,
                maturity_years=float(request.maturity_years),
                yield_curve=yield_curve,
                tenor_buckets=STANDARD_TENOR_BUCKETS,
            )

            from kinetix.risk import risk_calculation_pb2
            buckets = [
                risk_calculation_pb2.KeyRateDurationBucket(
                    tenor_label=b.tenor_label,
                    tenor_days=b.tenor_days,
                    dv01=str(round(b.dv01, 6)),
                )
                for b in result.krd_buckets
            ]
            return risk_calculation_pb2.KeyRateDurationResponse(
                instrument_id=request.instrument_id,
                krd_buckets=buckets,
                total_dv01=str(round(result.total_dv01, 6)),
            )
        except ValueError as e:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(e))
        except Exception as e:
            logger.exception("CalculateKeyRateDurations failed")
            context.abort(grpc.StatusCode.INTERNAL, str(e))


def build_grpc_server_interceptors() -> list:
    """Builds the gRPC server interceptor chain.

    When ``OTEL_EXPORTER_OTLP_ENDPOINT`` is set, includes the OpenTelemetry
    gRPC server interceptor. The interceptor extracts the inbound W3C
    ``traceparent`` from request metadata and continues the trace context,
    so spans created while handling the RPC become children of the calling
    service's span (e.g. risk-orchestrator → risk-engine trace stitching).

    Returns an empty list when no OTLP endpoint is configured, matching the
    rest of the OTel setup which is gated on the same environment variable.
    """
    interceptors: list = []
    if os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT"):
        try:
            from opentelemetry.instrumentation.grpc import server_interceptor

            interceptors.append(server_interceptor())
            logger.info("OTel gRPC server interceptor enabled — inbound traces will be continued")
        except Exception:
            logger.warning(
                "Failed to configure OTel gRPC server interceptor, continuing without it",
                exc_info=True,
            )
    return interceptors


def serve(port: int = 50051, metrics_port: int = 9091, models_dir: str = "models"):
    _json_handler = logging.StreamHandler()
    _json_handler.setFormatter(JsonLogFormatter())
    logging.basicConfig(level=logging.INFO, handlers=[_json_handler])

    # Initialise OTel tracing SDK (no-op if endpoint not configured)
    from kinetix_risk.otel_init import init_otel
    init_otel()

    # Configure OTel logging if endpoint is set
    otel_endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT")
    if otel_endpoint:
        try:
            from opentelemetry.sdk._logs import LoggerProvider
            from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
            from opentelemetry.exporter.otlp.proto.grpc._log_exporter import OTLPLogExporter
            from opentelemetry.sdk.resources import Resource

            resource = Resource.create({"service.name": os.environ.get("OTEL_SERVICE_NAME", "risk-engine")})
            provider = LoggerProvider(resource=resource)
            provider.add_log_record_processor(BatchLogRecordProcessor(OTLPLogExporter(endpoint=otel_endpoint, insecure=True)))

            from opentelemetry._logs import set_logger_provider
            set_logger_provider(provider)

            from opentelemetry.sdk._logs.export import LoggingHandler
            handler = LoggingHandler(level=logging.INFO, logger_provider=provider)
            logging.getLogger().addHandler(handler)
            logger.info("OTel logging configured, exporting to %s", otel_endpoint)
        except Exception:
            logger.warning("Failed to configure OTel logging, continuing without it", exc_info=True)

    prometheus_client.start_http_server(metrics_port)
    logger.info("Prometheus metrics server started on port %d", metrics_port)

    # Thread pool sizing: defaults to max(10, cpu_count) so the pool covers at
    # least one thread per core on any host. Override via GRPC_THREAD_POOL_SIZE.
    # See risk-engine/THREAD_POOL_TUNING.md for the benchmark rationale.
    _default_pool_size = max(10, os.cpu_count() or 10)
    _pool_size = int(os.environ.get("GRPC_THREAD_POOL_SIZE", _default_pool_size))
    logger.info("gRPC thread pool size: %d (GRPC_THREAD_POOL_SIZE=%s)", _pool_size, os.environ.get("GRPC_THREAD_POOL_SIZE", "unset"))

    max_message_size = 50 * 1024 * 1024  # 50 MB
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=_pool_size),
        interceptors=build_grpc_server_interceptors(),
        options=[
            ("grpc.max_send_message_length", max_message_size),
            ("grpc.max_receive_message_length", max_message_size),
        ],
    )
    risk_calculation_pb2_grpc.add_RiskCalculationServiceServicer_to_server(
        RiskCalculationServicer(), server
    )
    model_store = ModelStore(Path(models_dir))
    ml_prediction_pb2_grpc.add_MLPredictionServiceServicer_to_server(
        MLPredictionServicer(model_store), server
    )
    stress_testing_pb2_grpc.add_StressTestServiceServicer_to_server(
        StressTestServicer(), server
    )
    regulatory_reporting_pb2_grpc.add_RegulatoryReportingServiceServicer_to_server(
        RegulatoryReportingServicer(), server
    )
    market_data_dependencies_pb2_grpc.add_MarketDataDependenciesServiceServicer_to_server(
        MarketDataDependenciesServicer(), server
    )
    liquidity_pb2_grpc.add_LiquidityRiskServiceServicer_to_server(
        LiquidityAdjustedVaRServicer(), server
    )
    counterparty_risk_pb2_grpc.add_CounterpartyRiskServiceServicer_to_server(
        CounterpartyRiskServicer(), server
    )
    counterparty_risk_pb2_grpc.add_SaCcrServiceServicer_to_server(
        SaCcrServicer(), server
    )
    attribution_pb2_grpc.add_AttributionServiceServicer_to_server(
        AttributionServicer(), server
    )

    tls_enabled = os.environ.get("GRPC_TLS_ENABLED", "false").lower() == "true"

    if tls_enabled:
        cert_path = os.environ.get("GRPC_TLS_CERT", "certs/server-cert.pem")
        key_path = os.environ.get("GRPC_TLS_KEY", "certs/server-key.pem")
        ca_path = os.environ.get("GRPC_TLS_CA", "certs/ca-cert.pem")

        with open(key_path, "rb") as f:
            private_key = f.read()
        with open(cert_path, "rb") as f:
            certificate_chain = f.read()
        with open(ca_path, "rb") as f:
            root_certificates = f.read()

        credentials = grpc.ssl_server_credentials(
            [(private_key, certificate_chain)],
            root_certificates=root_certificates,
            require_client_auth=False,
        )
        server.add_secure_port(f"[::]:{port}", credentials)
        logger.info("Risk engine gRPC server started on port %d with TLS", port)
    else:
        server.add_insecure_port(f"[::]:{port}")
        logger.info("Risk engine gRPC server started on port %d (plaintext)", port)

    server.start()

    def handle_sigterm(signum, frame):
        logger.info("SIGTERM received, initiating graceful shutdown")
        stopped = server.stop(grace=30)
        stopped.wait()
        logger.info("gRPC server stopped cleanly")

    signal.signal(signal.SIGTERM, handle_sigterm)

    server.wait_for_termination()


if __name__ == "__main__":
    serve()
