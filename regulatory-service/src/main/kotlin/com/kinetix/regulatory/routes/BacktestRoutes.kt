package com.kinetix.regulatory.routes

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.regulatory.audit.GovernanceAuditPublisher
import com.kinetix.regulatory.dtos.BacktestComparisonResponse
import com.kinetix.regulatory.dtos.BacktestHistoryResponse
import com.kinetix.regulatory.dtos.BacktestRequest
import com.kinetix.regulatory.dtos.BacktestResultResponse
import com.kinetix.regulatory.model.BacktestComparison
import com.kinetix.regulatory.model.BacktestResultRecord
import com.kinetix.regulatory.persistence.BacktestResultRepository
import com.kinetix.regulatory.service.BacktestComparisonService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ln

private val backtestLogger = LoggerFactory.getLogger("com.kinetix.regulatory.routes.BacktestRoutes")

fun Route.backtestRoutes(
    repository: BacktestResultRepository,
    comparisonService: BacktestComparisonService? = null,
    auditPublisher: GovernanceAuditPublisher? = null,
) {
    route("/api/v1/regulatory/backtest/{bookId}") {
        post({
            summary = "Trigger VaR backtest for a book"
            tags = listOf("Backtesting")
            request {
                pathParameter<String>("bookId") { description = "Book identifier" }
            }
        }) {
            val bookId = call.parameters["bookId"]
                ?: throw IllegalArgumentException("Missing required path parameter: bookId")

            val request = call.receive<BacktestRequest>()
            backtestLogger.info("Backtest requested for book={}, days={}, confidenceLevel={}", bookId, request.dailyVarPredictions.size, request.confidenceLevel)

            if (request.dailyVarPredictions.size != request.dailyPnl.size) {
                throw IllegalArgumentException("dailyVarPredictions and dailyPnl must have the same length")
            }
            if (request.dailyVarPredictions.isEmpty()) {
                throw IllegalArgumentException("Cannot run backtest on empty data")
            }

            val result = runBacktest(request)
            val digest = digestBacktestInputs(request)
            val today = LocalDate.now()

            val record = BacktestResultRecord(
                id = UUID.randomUUID().toString(),
                bookId = bookId,
                calculationType = request.calculationType,
                confidenceLevel = request.confidenceLevel,
                totalDays = result.totalDays,
                violationCount = result.violationCount,
                violationRate = result.violationRate,
                kupiecStatistic = result.kupiecStatistic,
                kupiecPValue = result.kupiecPValue,
                kupiecPass = result.kupiecPass,
                christoffersenStatistic = result.christoffersenStatistic,
                christoffersenPValue = result.christoffersenPValue,
                christoffersenPass = result.christoffersenPass,
                trafficLightZone = result.trafficLightZone,
                calculatedAt = Instant.now(),
                inputDigest = digest,
                windowStart = today.minusDays(result.totalDays.toLong()),
                windowEnd = today,
            )

            repository.save(record)
            backtestLogger.info("Backtest completed for book={}, violations={}/{}, zone={}", bookId, record.violationCount, record.totalDays, record.trafficLightZone)
            auditPublisher?.publish(
                GovernanceAuditEvent(
                    eventType = AuditEventType.REPORT_GENERATED,
                    userId = "SYSTEM",
                    userRole = "SYSTEM",
                    bookId = bookId,
                    details = "BACKTEST",
                    correlationId = MDC.get("correlationId"),
                )
            )
            call.respond(HttpStatusCode.Created, record.toResponse())
        }

        get("/latest", {
            summary = "Get latest backtest result"
            tags = listOf("Backtesting")
            request {
                pathParameter<String>("bookId") { description = "Book identifier" }
            }
        }) {
            val bookId = call.parameters["bookId"]
                ?: throw IllegalArgumentException("Missing required path parameter: bookId")

            val record = repository.findLatestByBookId(bookId)
            if (record != null) {
                call.respond(record.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/compare", {
            summary = "Compare two backtest results"
            tags = listOf("Backtesting")
            request {
                queryParameter<String>("baseId") { description = "ID of the base backtest result" }
                queryParameter<String>("targetId") { description = "ID of the target backtest result" }
            }
        }) {
            val service = comparisonService
                ?: return@get call.respond(HttpStatusCode.NotImplemented, "Comparison service not available")

            val baseId = call.request.queryParameters["baseId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing baseId parameter")
            val targetId = call.request.queryParameters["targetId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing targetId parameter")

            val comparison = service.compare(baseId, targetId)
            call.respond(comparison.toResponse())
        }

        get("/history", {
            summary = "Get backtest history"
            tags = listOf("Backtesting")
            request {
                pathParameter<String>("bookId") { description = "Book identifier" }
                queryParameter<String>("limit") {
                    description = "Maximum number of results"
                    required = false
                }
                queryParameter<String>("offset") {
                    description = "Number of results to skip"
                    required = false
                }
            }
        }) {
            val bookId = call.parameters["bookId"]
                ?: throw IllegalArgumentException("Missing required path parameter: bookId")
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 20
            val offset = call.queryParameters["offset"]?.toIntOrNull() ?: 0

            val records = repository.findByBookId(bookId, limit, offset)
            call.respond(
                BacktestHistoryResponse(
                    results = records.map { it.toResponse() },
                    total = records.size,
                    limit = limit,
                    offset = offset,
                ),
            )
        }
    }
}

private fun digestBacktestInputs(request: BacktestRequest): String {
    val canonical = buildString {
        append("calc=${request.calculationType}")
        append("|conf=${"%.15g".format(request.confidenceLevel)}")
        append("|days=${request.dailyVarPredictions.size}")
        append("|var=")
        request.dailyVarPredictions.joinTo(this, ",") { "%.15g".format(it) }
        append("|pnl=")
        request.dailyPnl.joinTo(this, ",") { "%.15g".format(it) }
    }
    return sha256(canonical)
}

private fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }
}

private data class BacktestComputation(
    val totalDays: Int,
    val violationCount: Int,
    val violationRate: Double,
    val kupiecStatistic: Double,
    val kupiecPValue: Double,
    val kupiecPass: Boolean,
    val christoffersenStatistic: Double,
    val christoffersenPValue: Double,
    val christoffersenPass: Boolean,
    val trafficLightZone: String,
)

private fun runBacktest(request: BacktestRequest): BacktestComputation {
    val totalDays = request.dailyVarPredictions.size
    val expectedRate = 1.0 - request.confidenceLevel

    var violationCount = 0
    for (i in 0 until totalDays) {
        val actualLoss = -request.dailyPnl[i]
        if (actualLoss > request.dailyVarPredictions[i]) {
            violationCount++
        }
    }

    val violationRate = if (totalDays > 0) violationCount.toDouble() / totalDays else 0.0

    val (kupiecStat, kupiecPVal) = kupiecPofTest(totalDays, violationCount, expectedRate)
    val kupiecPass = kupiecPVal > 0.05

    val (christStat, christPVal) = christoffersenTest(request.dailyVarPredictions, request.dailyPnl)
    val christPass = christPVal > 0.05

    val zone = trafficLightZone(violationCount)

    return BacktestComputation(
        totalDays = totalDays,
        violationCount = violationCount,
        violationRate = violationRate,
        kupiecStatistic = kupiecStat,
        kupiecPValue = kupiecPVal,
        kupiecPass = kupiecPass,
        christoffersenStatistic = christStat,
        christoffersenPValue = christPVal,
        christoffersenPass = christPass,
        trafficLightZone = zone,
    )
}

private fun kupiecPofTest(totalDays: Int, violations: Int, expectedRate: Double): Pair<Double, Double> {
    val n = violations
    val t = totalDays
    val p = expectedRate

    val observedRate = when {
        n == 0 -> 1e-10
        n == t -> 1.0 - 1e-10
        else -> n.toDouble() / t
    }

    val lr = -2.0 * (
        n * ln(p) + (t - n) * ln(1.0 - p)
        - n * ln(observedRate) - (t - n) * ln(1.0 - observedRate)
    )

    val pValue = 1.0 - chi2Cdf(lr, 1)
    return Pair(lr, pValue)
}

private fun christoffersenTest(varPredictions: List<Double>, pnl: List<Double>): Pair<Double, Double> {
    val indicators = pnl.indices.map { i ->
        if (-pnl[i] > varPredictions[i]) 1 else 0
    }

    var n00 = 0; var n01 = 0; var n10 = 0; var n11 = 0
    for (i in 0 until indicators.size - 1) {
        when (Pair(indicators[i], indicators[i + 1])) {
            Pair(0, 0) -> n00++
            Pair(0, 1) -> n01++
            Pair(1, 0) -> n10++
            Pair(1, 1) -> n11++
        }
    }

    val totalTransitions = n00 + n01 + n10 + n11
    if (totalTransitions == 0) return Pair(0.0, 1.0)

    val row0 = n00 + n01
    val row1 = n10 + n11
    if (row0 == 0 || row1 == 0) return Pair(0.0, 1.0)

    val pi01 = n01.toDouble() / row0
    val pi11 = n11.toDouble() / row1
    val pi = (n01 + n11).toDouble() / totalTransitions

    if (pi <= 0.0 || pi >= 1.0) return Pair(0.0, 1.0)
    if (pi01 <= 0.0 || pi01 >= 1.0 || pi11 <= 0.0 || pi11 >= 1.0) return Pair(0.0, 1.0)

    val ll0 = (n00 + n10) * ln(1.0 - pi) + (n01 + n11) * ln(pi)

    var ll1 = 0.0
    if (n00 > 0) ll1 += n00 * ln(1.0 - pi01)
    if (n01 > 0) ll1 += n01 * ln(pi01)
    if (n10 > 0) ll1 += n10 * ln(1.0 - pi11)
    if (n11 > 0) ll1 += n11 * ln(pi11)

    val lrInd = -2.0 * (ll0 - ll1)
    val pValue = 1.0 - chi2Cdf(lrInd, 1)
    return Pair(lrInd, pValue)
}

private fun trafficLightZone(violationCount: Int): String = when {
    violationCount <= 4 -> "GREEN"
    violationCount <= 9 -> "YELLOW"
    else -> "RED"
}

private fun chi2Cdf(x: Double, degreesOfFreedom: Int): Double {
    if (x <= 0.0) return 0.0
    return regularizedGammaP(degreesOfFreedom / 2.0, x / 2.0)
}

private fun regularizedGammaP(a: Double, x: Double): Double {
    if (x < a + 1.0) {
        var sum = 1.0 / a
        var term = 1.0 / a
        for (n in 1..200) {
            term *= x / (a + n)
            sum += term
            if (abs(term) < 1e-12 * abs(sum)) break
        }
        return sum * kotlin.math.exp(-x + a * ln(x) - lnGamma(a))
    } else {
        return 1.0 - regularizedGammaQ(a, x)
    }
}

private fun regularizedGammaQ(a: Double, x: Double): Double {
    var f = 1.0
    var c = 1.0
    var d = 1.0 / (x + 1.0 - a)
    f = d
    for (n in 1..200) {
        val an = -n * (n - a)
        val bn = x + 2.0 * n + 1.0 - a
        d = an * d + bn
        if (abs(d) < 1e-30) d = 1e-30
        c = bn + an / c
        if (abs(c) < 1e-30) c = 1e-30
        d = 1.0 / d
        val delta = c * d
        f *= delta
        if (abs(delta - 1.0) < 1e-12) break
    }
    return f * kotlin.math.exp(-x + a * ln(x) - lnGamma(a))
}

private fun lnGamma(x: Double): Double {
    val coefficients = doubleArrayOf(
        76.18009172947146, -86.50532032941677, 24.01409824083091,
        -1.231739572450155, 0.001208650973866179, -0.000005395239384953
    )
    var y = x
    var tmp = x + 5.5
    tmp -= (x - 0.5) * ln(tmp)
    var ser = 1.000000000190015
    for (j in coefficients.indices) {
        y += 1.0
        ser += coefficients[j] / y
    }
    return -tmp + ln(2.5066282746310005 * ser / x)
}

private fun BacktestComparison.toResponse() = BacktestComparisonResponse(
    baseCalculationType = baseConfig.calculationType,
    baseConfidenceLevel = "%.4f".format(baseConfig.confidenceLevel),
    baseTotalDays = baseConfig.totalDays,
    baseViolationCount = baseViolationCount,
    baseViolationRate = "%.4f".format(baseViolationRate),
    baseKupiecPValue = "%.4f".format(baseKupiecPValue),
    baseChristoffersenPValue = "%.4f".format(baseChristoffersenPValue),
    baseTrafficLightZone = baseTrafficLightZone,
    targetCalculationType = targetConfig.calculationType,
    targetConfidenceLevel = "%.4f".format(targetConfig.confidenceLevel),
    targetTotalDays = targetConfig.totalDays,
    targetViolationCount = targetViolationCount,
    targetViolationRate = "%.4f".format(targetViolationRate),
    targetKupiecPValue = "%.4f".format(targetKupiecPValue),
    targetChristoffersenPValue = "%.4f".format(targetChristoffersenPValue),
    targetTrafficLightZone = targetTrafficLightZone,
    trafficLightChanged = trafficLightChanged,
)

private fun BacktestResultRecord.toResponse() = BacktestResultResponse(
    id = id,
    bookId = bookId,
    calculationType = calculationType,
    confidenceLevel = "%.4f".format(confidenceLevel),
    totalDays = totalDays,
    violationCount = violationCount,
    violationRate = "%.6f".format(violationRate),
    kupiecStatistic = "%.4f".format(kupiecStatistic),
    kupiecPValue = "%.4f".format(kupiecPValue),
    kupiecPass = kupiecPass,
    christoffersenStatistic = "%.4f".format(christoffersenStatistic),
    christoffersenPValue = "%.4f".format(christoffersenPValue),
    christoffersenPass = christoffersenPass,
    trafficLightZone = trafficLightZone,
    calculatedAt = calculatedAt.toString(),
)
