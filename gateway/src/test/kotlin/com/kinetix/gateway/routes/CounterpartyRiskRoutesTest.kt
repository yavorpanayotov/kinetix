package com.kinetix.gateway.routes

import com.kinetix.common.dtos.CreatePositionNoteRequest
import com.kinetix.common.dtos.PositionNoteDto
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.Side
import com.kinetix.common.model.Trade
import com.kinetix.common.model.TradeId
import com.kinetix.common.model.TradeStatus
import com.kinetix.gateway.client.BookTradeCommand
import com.kinetix.gateway.client.BookTradeResult
import com.kinetix.gateway.client.PortfolioAggregationSummary
import com.kinetix.gateway.client.PortfolioSummary
import com.kinetix.gateway.client.PositionServiceClient
import com.kinetix.gateway.client.RiskServiceClient
import com.kinetix.gateway.client.TradeBlotterRow
import com.kinetix.gateway.client.TradeHistoryPage
import com.kinetix.gateway.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val sampleExposure = buildJsonObject {
    put("counterpartyId", "CP-GS")
    put("calculatedAt", "2026-03-24T10:00:00Z")
    put("currentNetExposure", 2_000_000.0)
    put("peakPfe", 1_800_000.0)
    put("cva", 12_500.0)
    put("cvaEstimated", false)
    put("currency", "USD")
    putJsonArray("pfeProfile") {}
}

private val sampleCva = buildJsonObject {
    put("counterpartyId", "CP-GS")
    put("cva", 12_500.0)
    put("isEstimated", false)
    put("hazardRate", 0.0065)
    put("pd1y", 0.0065)
}

class CounterpartyRiskRoutesTest : FunSpec({

    val riskClient = mockk<RiskServiceClient>(relaxed = true)

    beforeEach {
        clearMocks(riskClient)
        coEvery { riskClient.calculateVaR(any()) } returns null
        coEvery { riskClient.getLatestVaR(any()) } returns null
    }

    test("GET /api/v1/counterparty-risk returns all counterparty exposures") {
        val exposureList = buildJsonArray { add(sampleExposure) }
        coEvery { riskClient.getAllCounterpartyExposures() } returns exposureList

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/counterparty-risk")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe exposureList.toString()
        }
    }

    test("GET /api/v1/counterparty-risk/{id} returns exposure for known counterparty") {
        coEvery { riskClient.getCounterpartyExposure("CP-GS") } returns sampleExposure

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/counterparty-risk/CP-GS")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe sampleExposure.toString()
        }
    }

    test("GET /api/v1/counterparty-risk/{id} returns 404 for unknown counterparty") {
        coEvery { riskClient.getCounterpartyExposure("CP-UNKNOWN") } returns null

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/counterparty-risk/CP-UNKNOWN")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /api/v1/counterparty-risk/{id}/history returns history") {
        val historyList = buildJsonArray { add(sampleExposure) }
        coEvery { riskClient.getCounterpartyExposureHistory("CP-GS", 90) } returns historyList

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/counterparty-risk/CP-GS/history")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe historyList.toString()
        }
    }

    test("POST /api/v1/counterparty-risk/{id}/pfe triggers PFE computation") {
        coEvery { riskClient.computeCounterpartyPFE("CP-GS", any()) } returns sampleExposure

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/counterparty-risk/CP-GS/pfe") {
                contentType(ContentType.Application.Json)
                setBody("""{"positions":[]}""")
            }
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe sampleExposure.toString()
        }
    }

    test("POST /api/v1/counterparty-risk/{id}/cva returns CVA for known counterparty") {
        coEvery { riskClient.computeCounterpartyCVA("CP-GS") } returns sampleCva

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/counterparty-risk/CP-GS/cva")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe sampleCva.toString()
        }
    }

    test("POST /api/v1/counterparty-risk/{id}/cva returns 404 when no PFE snapshot") {
        coEvery { riskClient.computeCounterpartyCVA("CP-NEW") } returns null

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/counterparty-risk/CP-NEW/cva")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    // ---------------------------------------------------------------------
    // kx-qfqn: the trade-counterparty enrichment fans out to position-service
    // per book. Under demo trade volume this is slow; an unbounded fan-out
    // hung the gateway response and left the Counterparty Risk tab stuck on a
    // perpetual spinner. The enrichment must be bounded by a deadline so the
    // canonical risk-orchestrator snapshot rows are still returned, and the
    // per-book calls must run concurrently so latency is bounded by the
    // slowest single call, not the sum.
    // ---------------------------------------------------------------------

    test("GET /api/v1/counterparty-risk returns the snapshot rows even when trade enrichment is too slow to finish") {
        coEvery { riskClient.getAllCounterpartyExposures() } returns
            buildJsonArray { add(sampleExposure) }

        // Enrichment per book takes far longer than the deadline we give it.
        val slowPositionClient = FakePositionServiceClient(
            books = listOf("book-1", "book-2"),
            tradeCounterpartiesByBook = mapOf(
                "book-1" to listOf("CP-SLOW-1"),
                "book-2" to listOf("CP-SLOW-2"),
            ),
            perBookDelayMs = 5_000,
        )

        testApplication {
            application {
                install(ContentNegotiation) {
                    json()
                }
                routing {
                    counterpartyRiskRoutes(riskClient, slowPositionClient, enrichmentTimeoutMs = 100)
                }
            }
            val response = client.get("/api/v1/counterparty-risk")
            response.status shouldBe HttpStatusCode.OK
            val ids = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                .map { it.jsonObject["counterpartyId"]!!.jsonPrimitive.content }
            // The canonical snapshot row survives; slow trade-only placeholders
            // are simply skipped rather than hanging the whole response.
            ids shouldContainAll listOf("CP-GS")
        }
    }

    test("GET /api/v1/counterparty-risk fans out to the books concurrently, not sequentially") {
        coEvery { riskClient.getAllCounterpartyExposures() } returns buildJsonArray { }

        // Three books, each taking ~300ms. Sequential would take ~900ms;
        // concurrent should finish in roughly one book's delay.
        val perBookDelayMs = 300L
        val client = FakePositionServiceClient(
            books = listOf("book-1", "book-2", "book-3"),
            tradeCounterpartiesByBook = mapOf(
                "book-1" to listOf("CP-A"),
                "book-2" to listOf("CP-B"),
                "book-3" to listOf("CP-C"),
            ),
            perBookDelayMs = perBookDelayMs,
        )

        testApplication {
            application {
                install(ContentNegotiation) {
                    json()
                }
                routing {
                    counterpartyRiskRoutes(riskClient, client, enrichmentTimeoutMs = 5_000)
                }
            }
            val start = System.currentTimeMillis()
            val response = this.client.get("/api/v1/counterparty-risk")
            val elapsed = System.currentTimeMillis() - start
            response.status shouldBe HttpStatusCode.OK
            val ids = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                .map { it.jsonObject["counterpartyId"]!!.jsonPrimitive.content }
            ids shouldContainAll listOf("CP-A", "CP-B", "CP-C")
            // Concurrent fan-out: well under the sequential sum (3 × 300ms).
            elapsed shouldBeLessThan (perBookDelayMs * 3)
        }
    }
})

/**
 * Test double for [PositionServiceClient] that lets a test control how long
 * each per-book trade-history call takes and which counterparties each book
 * carries. Only the methods used by the counterparty-risk enrichment path are
 * meaningful; the rest throw so an accidental dependency is loud.
 */
private class FakePositionServiceClient(
    private val books: List<String>,
    private val tradeCounterpartiesByBook: Map<String, List<String>>,
    private val perBookDelayMs: Long,
) : PositionServiceClient {

    val tradeHistoryCalls = AtomicInteger(0)

    override suspend fun listPortfolios(): List<PortfolioSummary> =
        books.map { PortfolioSummary(BookId(it)) }

    override suspend fun getTradeHistory(bookId: BookId): List<TradeBlotterRow> {
        tradeHistoryCalls.incrementAndGet()
        delay(perBookDelayMs)
        return (tradeCounterpartiesByBook[bookId.value] ?: emptyList()).map { cp ->
            tradeBlotterRow(bookId.value, cp)
        }
    }

    override suspend fun bookTrade(command: BookTradeCommand): BookTradeResult =
        throw UnsupportedOperationException()

    override suspend fun getPositions(bookId: BookId): List<Position> =
        throw UnsupportedOperationException()

    override suspend fun getTradeHistoryPage(
        bookId: BookId,
        offset: Long,
        limit: Int,
        counterpartyId: String?,
    ): TradeHistoryPage = throw UnsupportedOperationException()

    override suspend fun getBookSummary(bookId: BookId, baseCurrency: String): PortfolioAggregationSummary =
        throw UnsupportedOperationException()

    override suspend fun aggregateAllBooks(baseCurrency: String): PortfolioAggregationSummary =
        throw UnsupportedOperationException()

    override suspend fun listPositionNotes(bookId: BookId, instrumentId: String?): List<PositionNoteDto> =
        throw UnsupportedOperationException()

    override suspend fun createPositionNote(
        bookId: BookId,
        request: CreatePositionNoteRequest,
        author: String?,
    ): PositionNoteDto = throw UnsupportedOperationException()

    override suspend fun deletePositionNote(id: String): Boolean =
        throw UnsupportedOperationException()
}

private fun tradeBlotterRow(bookId: String, counterpartyId: String): TradeBlotterRow =
    TradeBlotterRow(
        trade = Trade(
            tradeId = TradeId("t-$bookId-$counterpartyId"),
            bookId = BookId(bookId),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal.ONE,
            price = Money(BigDecimal("100.00"), java.util.Currency.getInstance("USD")),
            tradedAt = Instant.parse("2026-05-28T10:00:00Z"),
            status = TradeStatus.LIVE,
            counterpartyId = counterpartyId,
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        ),
    )
