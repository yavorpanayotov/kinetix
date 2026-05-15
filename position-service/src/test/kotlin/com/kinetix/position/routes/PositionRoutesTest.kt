package com.kinetix.position.routes

import com.kinetix.common.model.*
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.persistence.TradeEventRepository
import com.kinetix.position.service.AmendTradeCommand
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.BookTradeResult
import com.kinetix.position.service.GetPositionsQuery
import com.kinetix.position.service.InvalidTradeStateException
import com.kinetix.position.service.PositionQueryService
import com.kinetix.position.service.TradeBookingService
import com.kinetix.position.service.PortfolioAggregationService
import com.kinetix.position.service.TradeLifecycleService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val PORTFOLIO = BookId("port-1")
private val AAPL = InstrumentId("AAPL")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)

private fun position(
    bookId: BookId = PORTFOLIO,
    instrumentId: InstrumentId = AAPL,
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    averageCost: String = "150.00",
    marketPrice: String = "155.00",
) = Position(
    bookId = bookId,
    instrumentId = instrumentId,
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = usd(averageCost),
    marketPrice = usd(marketPrice),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

@Serializable
private data class ErrorBody(val error: String, val message: String)

private fun Application.configureTestApp(
    positionRepository: PositionRepository,
    positionQueryService: PositionQueryService,
    tradeBookingService: TradeBookingService,
    tradeEventRepository: TradeEventRepository,
    tradeLifecycleService: TradeLifecycleService,
    portfolioAggregationService: PortfolioAggregationService,
) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<com.kinetix.position.service.TradeNotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorBody("trade_not_found", cause.message ?: "Trade not found"),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody("bad_request", cause.message ?: "Invalid request"),
            )
        }
    }
    routing {
        positionRoutes(positionRepository, positionQueryService, tradeBookingService, tradeEventRepository, tradeLifecycleService, portfolioAggregationService)
    }
}

class PositionRoutesTest : FunSpec({

    val positionRepository = mockk<PositionRepository>()
    val positionQueryService = mockk<PositionQueryService>()
    val tradeBookingService = mockk<TradeBookingService>()
    val tradeEventRepository = mockk<TradeEventRepository>()
    val tradeLifecycleService = mockk<TradeLifecycleService>()
    val portfolioAggregationService = mockk<PortfolioAggregationService>()

    beforeEach {
        clearMocks(positionRepository, positionQueryService, tradeBookingService, tradeEventRepository, tradeLifecycleService, portfolioAggregationService)
    }

    fun ApplicationTestBuilder.setupApp() {
        application {
            configureTestApp(positionRepository, positionQueryService, tradeBookingService, tradeEventRepository, tradeLifecycleService, portfolioAggregationService)
        }
    }

    test("GET /api/v1/books returns 200 with list of portfolio summaries") {
        testApplication {
            setupApp()
            coEvery { positionRepository.findDistinctBookIds() } returns listOf(
                BookId("port-1"),
                BookId("port-2"),
            )

            val response = client.get("/api/v1/books")

            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"bookId\":\"port-1\""
            body shouldContain "\"bookId\":\"port-2\""
        }
    }

    test("GET /api/v1/books returns empty list when no portfolios exist") {
        testApplication {
            setupApp()
            coEvery { positionRepository.findDistinctBookIds() } returns emptyList()

            val response = client.get("/api/v1/books")

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "[]"
        }
    }

    test("GET /api/v1/books/{id}/positions returns 200 with positions") {
        testApplication {
            setupApp()
            val positions = listOf(
                position(instrumentId = AAPL),
                position(instrumentId = InstrumentId("MSFT"), averageCost = "300.00", marketPrice = "310.00"),
            )
            coEvery { positionQueryService.handle(GetPositionsQuery(PORTFOLIO)) } returns positions

            val response = client.get("/api/v1/books/port-1/positions")

            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"instrumentId\":\"AAPL\""
            body shouldContain "\"instrumentId\":\"MSFT\""
            body shouldContain "\"assetClass\":\"EQUITY\""
            // marketValue = 100 * 155.00 = 15500.00
            body shouldContain "\"marketValue\":{\"amount\":\"15500.00\",\"currency\":\"USD\"}"
            // unrealizedPnl = (155.00 - 150.00) * 100 = 500.00
            body shouldContain "\"unrealizedPnl\":{\"amount\":\"500.00\",\"currency\":\"USD\"}"
        }
    }

    test("GET /api/v1/books/{id}/positions returns empty list for unknown portfolio") {
        testApplication {
            setupApp()
            coEvery { positionQueryService.handle(GetPositionsQuery(BookId("unknown"))) } returns emptyList()

            val response = client.get("/api/v1/books/unknown/positions")

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "[]"
        }
    }

    test("POST /api/v1/books/{id}/trades returns 201 with trade and position") {
        testApplication {
            setupApp()
            val trade = Trade(
                tradeId = TradeId("t-1"),
                bookId = PORTFOLIO,
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = usd("150.00"),
                tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            )
            val pos = position()
            coEvery { tradeBookingService.handle(any<BookTradeCommand>()) } returns BookTradeResult(trade, pos)

            val response = client.post("/api/v1/books/port-1/trades") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "tradeId": "t-1",
                        "instrumentId": "AAPL",
                        "assetClass": "EQUITY",
                        "side": "BUY",
                        "quantity": "100",
                        "priceAmount": "150.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY","traderId":"tr-test-001"}
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.Created
            val body = response.bodyAsText()
            body shouldContain "\"tradeId\":\"t-1\""
            body shouldContain "\"side\":\"BUY\""
            body shouldContain "\"bookId\":\"port-1\""
            body shouldContain "\"instrumentId\":\"AAPL\""
        }
    }

    test("GET /api/v1/books/{id}/trades returns 200 with trade history") {
        testApplication {
            setupApp()
            val trades = listOf(
                Trade(
                    tradeId = TradeId("t-1"),
                    bookId = PORTFOLIO,
                    instrumentId = AAPL,
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = usd("150.00"),
                    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
                ),
            )
            coEvery { tradeEventRepository.findByBookId(PORTFOLIO) } returns trades

            val response = client.get("/api/v1/books/port-1/trades")

            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"tradeId\":\"t-1\""
            body shouldContain "\"side\":\"BUY\""
            body shouldContain "\"quantity\":\"100\""
        }
    }

    test("GET /api/v1/books/{id}/trades returns empty list for unknown portfolio") {
        testApplication {
            setupApp()
            coEvery { tradeEventRepository.findByBookId(BookId("unknown")) } returns emptyList()

            val response = client.get("/api/v1/books/unknown/trades")

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "[]"
        }
    }

    test("GET /api/v1/books/{id}/trades/page returns paged history with total/hasMore metadata") {
        testApplication {
            setupApp()
            val trades = listOf(
                Trade(
                    tradeId = TradeId("page-1"),
                    bookId = PORTFOLIO,
                    instrumentId = AAPL,
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = usd("150.00"),
                    tradedAt = Instant.parse("2026-01-02T10:00:00Z"),
                    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
                ),
                Trade(
                    tradeId = TradeId("page-2"),
                    bookId = PORTFOLIO,
                    instrumentId = AAPL,
                    assetClass = AssetClass.EQUITY,
                    side = Side.SELL,
                    quantity = BigDecimal("50"),
                    price = usd("151.00"),
                    tradedAt = Instant.parse("2026-01-01T10:00:00Z"),
                    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
                ),
            )
            coEvery { tradeEventRepository.findByBookIdPaged(PORTFOLIO, 0, 2, null) } returns trades
            coEvery { tradeEventRepository.countByBookId(PORTFOLIO, null) } returns 5L

            val response = client.get("/api/v1/books/port-1/trades/page?offset=0&limit=2")

            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"total\":5"
            body shouldContain "\"hasMore\":true"
            body shouldContain "\"limit\":2"
            body shouldContain "\"offset\":0"
            body shouldContain "page-1"
            body shouldContain "page-2"
        }
    }

    test("GET /api/v1/books/{id}/trades/page forwards counterpartyId filter to the repository") {
        testApplication {
            setupApp()
            coEvery { tradeEventRepository.findByBookIdPaged(PORTFOLIO, 0, 100, "CP-GS") } returns emptyList()
            coEvery { tradeEventRepository.countByBookId(PORTFOLIO, "CP-GS") } returns 0L

            val response = client.get("/api/v1/books/port-1/trades/page?counterpartyId=CP-GS")

            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"total\":0"
            body shouldContain "\"hasMore\":false"
        }
    }

    test("GET /api/v1/books/{id}/trades/page clamps limit to the 1..1000 range") {
        testApplication {
            setupApp()
            // Excessive limit should be clamped to 1000.
            coEvery { tradeEventRepository.findByBookIdPaged(PORTFOLIO, 0, 1000, null) } returns emptyList()
            coEvery { tradeEventRepository.countByBookId(PORTFOLIO, null) } returns 0L

            val response = client.get("/api/v1/books/port-1/trades/page?limit=999999")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"limit\":1000"
        }
    }

    test("POST /api/v1/books/{id}/trades returns 400 for negative quantity") {
        testApplication {
            setupApp()

            val response = client.post("/api/v1/books/port-1/trades") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "instrumentId": "AAPL",
                        "assetClass": "EQUITY",
                        "side": "BUY",
                        "quantity": "-10",
                        "priceAmount": "150.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.BadRequest
            val body = response.bodyAsText()
            body shouldContain "bad_request"
            body shouldContain "Trade quantity must be positive"
        }
    }

    test("amend rejects non-live trade with 409 Conflict") {
        testApplication {
            setupApp()
            coEvery {
                tradeLifecycleService.handleAmend(any<AmendTradeCommand>())
            } throws InvalidTradeStateException("t-amended", TradeStatus.AMENDED, "amend")

            val response = client.put("/api/v1/books/port-1/trades/t-amended") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "instrumentId": "AAPL",
                        "assetClass": "EQUITY",
                        "side": "BUY",
                        "quantity": "200",
                        "priceAmount": "160.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.Conflict
            val body = response.bodyAsText()
            body shouldContain "invalid_trade_state"
            body shouldContain "t-amended"
        }
    }

    test("cancel rejects non-live trade with 409 Conflict") {
        testApplication {
            setupApp()
            coEvery {
                tradeLifecycleService.handleCancel(any())
            } throws InvalidTradeStateException("t-cancelled", TradeStatus.CANCELLED, "cancel")

            val response = client.delete("/api/v1/books/port-1/trades/t-cancelled")

            response.status shouldBe HttpStatusCode.Conflict
            val body = response.bodyAsText()
            body shouldContain "invalid_trade_state"
            body shouldContain "t-cancelled"
        }
    }
})
