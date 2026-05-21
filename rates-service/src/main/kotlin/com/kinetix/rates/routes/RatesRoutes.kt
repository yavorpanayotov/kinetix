package com.kinetix.rates.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.CurvePoint
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.RateSource
import com.kinetix.common.model.RiskFreeRate
import com.kinetix.common.model.Tenor
import com.kinetix.common.model.YieldCurve
import com.kinetix.rates.persistence.ForwardCurveRepository
import com.kinetix.rates.persistence.RiskFreeRateRepository
import com.kinetix.rates.persistence.YieldCurveRepository
import com.kinetix.rates.routes.dtos.*
import com.kinetix.rates.service.RateIngestionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

fun Route.ratesRoutes(
    yieldCurveRepository: YieldCurveRepository,
    riskFreeRateRepository: RiskFreeRateRepository,
    forwardCurveRepository: ForwardCurveRepository,
    ingestionService: RateIngestionService,
) {
    val log = org.slf4j.LoggerFactory.getLogger("com.kinetix.rates.routes.RatesRoutes")

    route("/api/v1/rates") {
        route("/yield-curves/{curveId}") {
            route("/latest") {
                get({
                    summary = "Get latest yield curve"
                    tags = listOf("Yield Curves")
                    request {
                        pathParameter<String>("curveId") { description = "Yield curve identifier" }
                    }
                }) {
                    val curveId = call.requirePathParam("curveId")
                    val curve = yieldCurveRepository.findLatest(curveId)
                    if (curve != null) {
                        call.respond(curve.toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }

            route("/tenor/{tenor}") {
                get({
                    summary = "Get the rate at a specific tenor; interpolates linearly when the node is missing"
                    tags = listOf("Yield Curves", "Data Quality")
                    request {
                        pathParameter<String>("curveId") { description = "Yield curve identifier" }
                        pathParameter<String>("tenor") { description = "Tenor label, e.g. 5Y" }
                    }
                    response {
                        code(HttpStatusCode.OK) { body<YieldCurveTenorResponse>() }
                        code(HttpStatusCode.NotFound) {}
                    }
                }) {
                    val curveId = call.requirePathParam("curveId")
                    val tenorLabel = call.requirePathParam("tenor")
                    val curve = yieldCurveRepository.findLatest(curveId)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val resolved = resolveTenor(curve, tenorLabel)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    if (resolved.interpolated) {
                        log.warn(
                            "Yield curve {} missing tenor {} — returning interpolated value {} (data_quality_intent=intentional_anomaly_demo)",
                            curveId, tenorLabel, resolved.value,
                        )
                    }
                    call.respond(
                        YieldCurveTenorResponse(
                            curveId = curveId,
                            tenor = tenorLabel,
                            value = resolved.value.toPlainString(),
                            interpolated = resolved.interpolated,
                        )
                    )
                }
            }

            route("/history") {
                get({
                    summary = "Get yield curve history"
                    tags = listOf("Yield Curves")
                    request {
                        pathParameter<String>("curveId") { description = "Yield curve identifier" }
                        queryParameter<String>("from") { description = "Start of time range" }
                        queryParameter<String>("to") { description = "End of time range" }
                    }
                }) {
                    val curveId = call.requirePathParam("curveId")
                    val from = call.queryParameters["from"]
                        ?: throw IllegalArgumentException("Missing required query parameter: from")
                    val to = call.queryParameters["to"]
                        ?: throw IllegalArgumentException("Missing required query parameter: to")
                    val curves = yieldCurveRepository.findByTimeRange(curveId, Instant.parse(from), Instant.parse(to))
                    call.respond(curves.map { it.toResponse() })
                }
            }
        }

        route("/yield-curves") {
            post({
                summary = "Ingest a yield curve"
                tags = listOf("Yield Curves")
                request {
                    body<IngestYieldCurveRequest>()
                }
            }) {
                val request = call.receive<IngestYieldCurveRequest>()
                require(request.tenors.isNotEmpty()) { "tenors must contain at least one entry" }
                val tenors = request.tenors.map { Tenor(it.label, it.days, BigDecimal(it.rate)) }
                require(tenors.zipWithNext().all { (a, b) -> a.days < b.days }) {
                    "tenors must be strictly monotonic in days"
                }
                val curve = YieldCurve(
                    curveId = request.curveId,
                    currency = Currency.getInstance(request.currency),
                    tenors = tenors,
                    asOf = Instant.now(),
                    source = RateSource.valueOf(request.source),
                )
                ingestionService.ingest(curve)
                call.respond(HttpStatusCode.Created, curve.toResponse())
            }
        }

        route("/risk-free/{currency}") {
            route("/latest") {
                get({
                    summary = "Get latest risk-free rate"
                    tags = listOf("Risk-Free Rates")
                    request {
                        pathParameter<String>("currency") { description = "Currency code" }
                        queryParameter<String>("tenor") { description = "Rate tenor" }
                    }
                }) {
                    val currencyCode = call.requirePathParam("currency")
                    val currency = Currency.getInstance(currencyCode)
                    val tenor = call.queryParameters["tenor"]
                        ?: throw IllegalArgumentException("Missing required query parameter: tenor")
                    val rate = riskFreeRateRepository.findLatest(currency, tenor)
                    if (rate != null) {
                        call.respond(rate.toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }

        route("/risk-free") {
            post({
                summary = "Ingest a risk-free rate"
                tags = listOf("Risk-Free Rates")
                request {
                    body<IngestRiskFreeRateRequest>()
                }
            }) {
                val request = call.receive<IngestRiskFreeRateRequest>()
                require(request.tenor.isNotBlank()) { "tenor must not be blank" }
                val rate = RiskFreeRate(
                    currency = Currency.getInstance(request.currency),
                    tenor = request.tenor,
                    rate = BigDecimal(request.rate).toDouble(),
                    asOfDate = Instant.now(),
                    source = RateSource.valueOf(request.source),
                )
                ingestionService.ingest(rate)
                call.respond(HttpStatusCode.Created, rate.toResponse())
            }
        }

        route("/forwards/{instrumentId}") {
            route("/latest") {
                get({
                    summary = "Get latest forward curve"
                    tags = listOf("Forward Curves")
                    request {
                        pathParameter<String>("instrumentId") { description = "Instrument identifier" }
                    }
                }) {
                    val instrumentId = InstrumentId(call.requirePathParam("instrumentId"))
                    val curve = forwardCurveRepository.findLatest(instrumentId)
                    if (curve != null) {
                        call.respond(curve.toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }

            route("/history") {
                get({
                    summary = "Get forward curve history"
                    tags = listOf("Forward Curves")
                    request {
                        pathParameter<String>("instrumentId") { description = "Instrument identifier" }
                        queryParameter<String>("from") { description = "Start of time range" }
                        queryParameter<String>("to") { description = "End of time range" }
                    }
                }) {
                    val instrumentId = InstrumentId(call.requirePathParam("instrumentId"))
                    val from = call.queryParameters["from"]
                        ?: throw IllegalArgumentException("Missing required query parameter: from")
                    val to = call.queryParameters["to"]
                        ?: throw IllegalArgumentException("Missing required query parameter: to")
                    val curves = forwardCurveRepository.findByTimeRange(instrumentId, Instant.parse(from), Instant.parse(to))
                    call.respond(curves.map { it.toResponse() })
                }
            }
        }

        route("/forwards") {
            post({
                summary = "Ingest a forward curve"
                tags = listOf("Forward Curves")
                request {
                    body<IngestForwardCurveRequest>()
                }
            }) {
                val request = call.receive<IngestForwardCurveRequest>()
                require(request.points.isNotEmpty()) { "points must contain at least one entry" }
                val curve = ForwardCurve(
                    instrumentId = InstrumentId(request.instrumentId),
                    assetClass = AssetClass.valueOf(request.assetClass),
                    points = request.points.map { CurvePoint(it.tenor, BigDecimal(it.value).toDouble()) },
                    asOfDate = Instant.now(),
                    source = RateSource.valueOf(request.source),
                )
                ingestionService.ingest(curve)
                call.respond(HttpStatusCode.Created, curve.toResponse())
            }
        }
    }
}

private fun YieldCurve.toResponse() = YieldCurveResponse(
    curveId = curveId,
    currency = currency.currencyCode,
    tenors = tenors.map { TenorDto(it.label, it.days, it.rate.toPlainString()) },
    asOfDate = asOf.toString(),
    source = source.name,
)

private data class ResolvedTenor(val value: BigDecimal, val interpolated: Boolean)

private val TENOR_DAYS: Map<String, Int> = mapOf(
    "O/N" to 1, "1W" to 7, "1M" to 30, "3M" to 90, "6M" to 180,
    "1Y" to 365, "2Y" to 730, "5Y" to 1825, "10Y" to 3650, "30Y" to 10950,
)

private fun resolveTenor(curve: YieldCurve, tenorLabel: String): ResolvedTenor? {
    curve.tenors.firstOrNull { it.label == tenorLabel }?.let {
        return ResolvedTenor(it.rate, interpolated = false)
    }
    val targetDays = TENOR_DAYS[tenorLabel] ?: curve.tenors.firstOrNull { it.label == tenorLabel }?.days
        ?: return null
    val sorted = curve.tenors.sortedBy { it.days }
    val below = sorted.lastOrNull { it.days < targetDays }
    val above = sorted.firstOrNull { it.days > targetDays }
    if (below == null || above == null) return null
    val span = (above.days - below.days).toBigDecimal()
    val offset = (targetDays - below.days).toBigDecimal()
    val interpolated = below.rate + (above.rate - below.rate) * offset.divide(span, java.math.MathContext.DECIMAL64)
    return ResolvedTenor(interpolated.setScale(6, java.math.RoundingMode.HALF_UP), interpolated = true)
}

private fun RiskFreeRate.toResponse() = RiskFreeRateResponse(
    currency = currency.currencyCode,
    tenor = tenor,
    rate = rate.toBigDecimal().toPlainString(),
    asOfDate = asOfDate.toString(),
    source = source.name,
)

private fun ForwardCurve.toResponse() = ForwardCurveResponse(
    instrumentId = instrumentId.value,
    assetClass = assetClass.name,
    points = points.map { CurvePointDto(it.tenor, it.value.toBigDecimal().toPlainString()) },
    asOfDate = asOfDate.toString(),
    source = source.name,
)
