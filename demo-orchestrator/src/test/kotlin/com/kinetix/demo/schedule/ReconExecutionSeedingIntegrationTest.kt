package com.kinetix.demo.schedule

import com.kinetix.demo.client.PositionServiceHttpClient
import com.kinetix.demo.client.dtos.StrategyTradeRequest
import com.kinetix.demo.profile.DemoBookProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Integration test for [SimulatedTraderJob]'s execution-cost and
 * reconciliation seeding, wired against a real local Ktor stub of
 * `position-service`.
 *
 * Per CLAUDE.md (Project Conventions / Acceptance tests), the HTTP boundary
 * to another Kinetix service is exercised over a real localhost HTTP/1.1
 * channel — never via `MockEngine`. The stub binds to port 0 and the
 * [PositionServiceHttpClient] is pointed at the resolved port, so
 * serialisation, content negotiation, and the HTTP wire are all real. This
 * mirrors the pattern used by [EodPromotionIntegrationTest] and
 * [SodBaselineCaptureIntegrationTest].
 *
 * The fake `position-service` mirrors the four routes the seeding flow
 * exercises, and a server-side reconciliation that produces a break row
 * whenever a prime-broker statement quantity differs from the trade quantity
 * by more than the 1-unit auto-resolve threshold — matching
 * `PrimeBrokerReconciliationService`. After one simulated trading-day tick
 * the test asserts that BOTH `GET /api/v1/execution/cost/{bookId}` AND
 * `GET /api/v1/execution/reconciliation/{bookId}` return >= 1 row, the
 * checkbox 7.1 contract.
 */
class ReconExecutionSeedingIntegrationTest : FunSpec({

    test("after a simulated trading day, the cost and reconciliation read paths each return at least one row") {
        val state = FakePositionServiceState()
        val stubServer = startFakePositionService(state)
        val port = runBlocking { stubServer.engine.resolvedConnectors().first().port }
        val baseUrl = "http://localhost:$port"

        val httpClient = HttpClient(CIO) {
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        try {
            val positionClient = PositionServiceHttpClient(
                httpClient = httpClient,
                baseUrl = baseUrl,
            )

            // balanced-income mirrors a canonical DevDataSeeder demo book.
            val book = DemoBookProfile(
                bookId = "balanced-income",
                tradeProbability = 1.0,
                instrumentIds = listOf("JNJ", "KO", "PG"),
                notionalRangeUsd = 50_000L..750_000L,
                assetClass = "EQUITY",
            )

            // 2026-05-18 is a Monday; noon UTC is inside the 09:00–16:30 window.
            val fixedClock = Clock.fixed(
                LocalDate.of(2026, 5, 18)
                    .atTime(12, 0)
                    .atZone(ZoneOffset.UTC)
                    .toInstant(),
                ZoneOffset.UTC,
            )

            val job = SimulatedTraderJob(
                positionClient = positionClient,
                strategyIdResolver = DefaultStrategyIdResolver(),
                priceBook = DefaultPriceBook(),
                books = listOf(book),
                tradingHoursStart = LocalTime.of(9, 0),
                tradingHoursEnd = LocalTime.of(16, 30),
                clock = fixedClock,
                random = Random(seed = 42L),
                // Force every booked trade to also upload a mismatched
                // statement so the reconciliation read path is deterministically
                // populated within a single tick.
                reconciliationBreakProbability = 1.0,
            )

            val posted = runBlocking { job.runTick() }
            posted shouldBeGreaterThan 0

            // GET execution cost — one sample seeded per booked trade.
            val costResponse = runBlocking {
                httpClient.get("$baseUrl/api/v1/execution/cost/balanced-income")
            }
            costResponse.status shouldBe HttpStatusCode.OK
            val costRows = jsonDecoder.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(FakeExecutionCostRow.serializer()),
                costResponse.bodyAsText(),
            )
            costRows.size shouldBeGreaterThanOrEqual 1
            costRows.size shouldBe posted

            // GET reconciliation — one reconciliation per booked trade, each
            // with at least one break row.
            val reconResponse = runBlocking {
                httpClient.get("$baseUrl/api/v1/execution/reconciliation/balanced-income")
            }
            reconResponse.status shouldBe HttpStatusCode.OK
            val reconRows = jsonDecoder.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(FakeReconciliationRow.serializer()),
                reconResponse.bodyAsText(),
            )
            reconRows.size shouldBeGreaterThanOrEqual 1
            reconRows.any { it.breakCount >= 1 } shouldBe true
        } finally {
            httpClient.close()
            stubServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }
})

private val jsonDecoder = Json { ignoreUnknownKeys = true }

// region — fake position-service

@Serializable
private data class FakeRecordExecutionCostRequest(
    val orderId: String,
    val instrumentId: String,
    val completedAt: String,
    val arrivalPrice: String,
    val averageFillPrice: String,
    val side: String,
    val totalQty: String,
    val slippageBps: String,
    val marketImpactBps: String? = null,
    val timingCostBps: String? = null,
    val totalCostBps: String,
)

@Serializable
private data class FakeExecutionCostRow(
    val orderId: String,
    val bookId: String,
    val instrumentId: String,
    val side: String,
    val slippageBps: String,
    val totalCostBps: String,
)

@Serializable
private data class FakePrimeBrokerPositionDto(
    val instrumentId: String,
    val quantity: String,
    val price: String,
)

@Serializable
private data class FakePrimeBrokerStatementRequest(
    val bookId: String,
    val date: String,
    val positions: List<FakePrimeBrokerPositionDto>,
)

@Serializable
private data class FakeReconciliationBreakRow(
    val instrumentId: String,
    val breakQty: String,
)

@Serializable
private data class FakeReconciliationRow(
    val reconciliationDate: String,
    val bookId: String,
    val status: String,
    val breakCount: Int,
    val breaks: List<FakeReconciliationBreakRow>,
)

private class FakePositionServiceState {
    /** bookId -> persisted execution-cost rows. */
    val executionCosts: ConcurrentHashMap<String, CopyOnWriteArrayList<FakeExecutionCostRow>> =
        ConcurrentHashMap()

    /** bookId -> persisted reconciliation rows. */
    val reconciliations: ConcurrentHashMap<String, CopyOnWriteArrayList<FakeReconciliationRow>> =
        ConcurrentHashMap()

    /** bookId|instrumentId -> internal traded quantity, accumulated per booked trade. */
    private val internalQty: ConcurrentHashMap<String, BigDecimal> = ConcurrentHashMap()

    fun recordTrade(bookId: String, instrumentId: String, quantity: BigDecimal) {
        internalQty.merge("$bookId|$instrumentId", quantity) { a, b -> a + b }
    }

    fun internalQtyFor(bookId: String, instrumentId: String): BigDecimal =
        internalQty["$bookId|$instrumentId"] ?: BigDecimal.ZERO

    fun addExecutionCost(row: FakeExecutionCostRow) {
        executionCosts.computeIfAbsent(row.bookId) { CopyOnWriteArrayList() }.add(row)
    }

    fun addReconciliation(row: FakeReconciliationRow) {
        reconciliations.computeIfAbsent(row.bookId) { CopyOnWriteArrayList() }.add(row)
    }
}

private val AUTO_RESOLVE_THRESHOLD = BigDecimal("1.0")

private fun startFakePositionService(state: FakePositionServiceState) =
    embeddedServer(Netty, port = 0) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
        }
        routing {
            // Book a trade — records the internal position so reconciliation
            // can detect a break against a mismatched prime-broker statement.
            post("/api/v1/books/{bookId}/strategies/{strategyId}/trades") {
                val bookId = call.parameters["bookId"].orEmpty()
                val request = call.receive<StrategyTradeRequest>()
                state.recordTrade(bookId, request.instrumentId, BigDecimal(request.quantity))
                call.respondText(
                    text = """{"tradeId":"${UUID.randomUUID()}"}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.Created,
                )
            }

            // Internal demo/seed endpoint — persist the execution-cost sample.
            post("/api/v1/internal/execution/cost/{bookId}") {
                val bookId = call.parameters["bookId"].orEmpty()
                val request = call.receive<FakeRecordExecutionCostRequest>()
                state.addExecutionCost(
                    FakeExecutionCostRow(
                        orderId = request.orderId,
                        bookId = bookId,
                        instrumentId = request.instrumentId,
                        side = request.side,
                        slippageBps = request.slippageBps,
                        totalCostBps = request.totalCostBps,
                    ),
                )
                call.respondText(
                    text = """{"orderId":"${request.orderId}","bookId":"$bookId"}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.Created,
                )
            }

            // Upload a prime-broker statement — runs a reconciliation that
            // mirrors PrimeBrokerReconciliationService: a quantity delta above
            // the 1-unit auto-resolve threshold produces a material break.
            post("/api/v1/execution/reconciliation/{bookId}/statements") {
                val bookId = call.parameters["bookId"].orEmpty()
                val request = call.receive<FakePrimeBrokerStatementRequest>()
                val breaks = request.positions.mapNotNull { pb ->
                    val internal = state.internalQtyFor(bookId, pb.instrumentId)
                    val pbQty = BigDecimal(pb.quantity)
                    val delta = (internal - pbQty).abs()
                    if (delta < AUTO_RESOLVE_THRESHOLD) {
                        null
                    } else {
                        FakeReconciliationBreakRow(
                            instrumentId = pb.instrumentId,
                            breakQty = (internal - pbQty).toPlainString(),
                        )
                    }
                }
                val row = FakeReconciliationRow(
                    reconciliationDate = request.date,
                    bookId = bookId,
                    status = if (breaks.isEmpty()) "CLEAN" else "BREAKS_FOUND",
                    breakCount = breaks.size,
                    breaks = breaks,
                )
                state.addReconciliation(row)
                call.respond(HttpStatusCode.Created, row)
            }

            get("/api/v1/execution/cost/{bookId}") {
                val bookId = call.parameters["bookId"].orEmpty()
                call.respond(state.executionCosts[bookId]?.toList() ?: emptyList<FakeExecutionCostRow>())
            }

            get("/api/v1/execution/reconciliation/{bookId}") {
                val bookId = call.parameters["bookId"].orEmpty()
                call.respond(state.reconciliations[bookId]?.toList() ?: emptyList<FakeReconciliationRow>())
            }
        }
    }.start(wait = false)

// endregion
