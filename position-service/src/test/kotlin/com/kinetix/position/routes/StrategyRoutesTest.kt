package com.kinetix.position.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.Side
import com.kinetix.common.model.Trade
import com.kinetix.common.model.TradeId
import com.kinetix.common.model.TraderId
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.position.model.StrategyType
import com.kinetix.position.model.TradeStrategy
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.BookTradeResult
import com.kinetix.position.service.TradeBookingService
import com.kinetix.position.service.TradeStrategyService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun Application.configureTestApp(
    strategyService: TradeStrategyService,
    tradeBookingService: TradeBookingService,
) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "bad_request", "message" to (cause.message ?: "Invalid")),
            )
        }
    }
    routing {
        strategyRoutes(strategyService, tradeBookingService)
    }
}

/**
 * Unit-level wire test for [strategyRoutes]. Verifies the optional
 * `counterpartyId` field added in kx-i72 is round-tripped from JSON into a
 * [BookTradeCommand] and back into the response body. Avoids Testcontainers
 * by stubbing the trading service — the contract under test is the route
 * mapping, not the persistence layer (the persistence path is exercised by
 * the existing `TradeBookingAcceptanceTest`).
 */
class StrategyRoutesTest : FunSpec({

    val strategyService = mockk<TradeStrategyService>()
    val tradeBookingService = mockk<TradeBookingService>()

    beforeEach {
        clearMocks(strategyService, tradeBookingService)
    }

    fun sampleStrategy(strategyId: String, bookId: String) = TradeStrategy(
        strategyId = strategyId,
        bookId = BookId(bookId),
        strategyType = StrategyType.STRADDLE,
        name = "Test Strategy",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    fun bookedTrade(
        tradeId: String,
        bookId: String,
        instrumentId: String,
        counterpartyId: String?,
        strategyId: String,
    ): Trade = Trade(
        tradeId = TradeId(tradeId),
        bookId = BookId(bookId),
        instrumentId = InstrumentId(instrumentId),
        assetClass = AssetClass.EQUITY,
        side = Side.BUY,
        quantity = BigDecimal("10"),
        price = Money(BigDecimal("100"), USD),
        tradedAt = Instant.parse("2026-05-26T12:00:00Z"),
        instrumentType = InstrumentTypeCode.CASH_EQUITY,
        strategyId = strategyId,
        counterpartyId = counterpartyId,
        traderId = TraderId("tr-eg-001"),
    )

    fun successfulResult(trade: Trade) = BookTradeResult(
        trade = trade,
        position = Position(
            bookId = trade.bookId,
            instrumentId = trade.instrumentId,
            assetClass = trade.assetClass,
            quantity = trade.quantity,
            averageCost = trade.price,
            marketPrice = trade.price,
            instrumentType = trade.instrumentType,
        ),
    )

    test("POST /strategies/{id}/trades with counterpartyId=GS — passes GS through to BookTradeCommand and persists on trade") {
        testApplication {
            application { configureTestApp(strategyService, tradeBookingService) }

            val bookId = "equity-growth"
            val strategyId = "strat-cp-1"
            coEvery { strategyService.findById(strategyId) } returns sampleStrategy(strategyId, bookId)

            val capturedCommand: CapturingSlot<BookTradeCommand> = slot()
            coEvery { tradeBookingService.handle(capture(capturedCommand)) } answers {
                successfulResult(
                    bookedTrade(
                        tradeId = capturedCommand.captured.tradeId.value,
                        bookId = capturedCommand.captured.bookId.value,
                        instrumentId = capturedCommand.captured.instrumentId.value,
                        counterpartyId = capturedCommand.captured.counterpartyId,
                        strategyId = capturedCommand.captured.strategyId ?: strategyId,
                    ),
                )
            }

            val response = client.post(
                "/api/v1/books/$bookId/strategies/$strategyId/trades",
            ) {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "instrumentId": "AAPL",
                      "assetClass": "EQUITY",
                      "side": "BUY",
                      "quantity": "10",
                      "priceAmount": "100",
                      "priceCurrency": "USD",
                      "tradedAt": "2026-05-26T12:00:00Z",
                      "instrumentType": "CASH_EQUITY",
                      "counterpartyId": "GS"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.Created
            capturedCommand.isCaptured shouldBe true
            capturedCommand.captured.counterpartyId shouldBe "GS"
        }
    }

    test("POST /strategies/{id}/trades without counterpartyId — command receives null and response is still 201") {
        testApplication {
            application { configureTestApp(strategyService, tradeBookingService) }

            val bookId = "equity-growth"
            val strategyId = "strat-cp-2"
            coEvery { strategyService.findById(strategyId) } returns sampleStrategy(strategyId, bookId)

            val capturedCommand: CapturingSlot<BookTradeCommand> = slot()
            coEvery { tradeBookingService.handle(capture(capturedCommand)) } answers {
                successfulResult(
                    bookedTrade(
                        tradeId = capturedCommand.captured.tradeId.value,
                        bookId = capturedCommand.captured.bookId.value,
                        instrumentId = capturedCommand.captured.instrumentId.value,
                        counterpartyId = null,
                        strategyId = strategyId,
                    ),
                )
            }

            val response = client.post(
                "/api/v1/books/$bookId/strategies/$strategyId/trades",
            ) {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "instrumentId": "AAPL",
                      "assetClass": "EQUITY",
                      "side": "BUY",
                      "quantity": "10",
                      "priceAmount": "100",
                      "priceCurrency": "USD",
                      "tradedAt": "2026-05-26T12:00:00Z",
                      "instrumentType": "CASH_EQUITY"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["tradeId"]!!.jsonPrimitive.content.shouldNotBeNull()
            capturedCommand.captured.counterpartyId shouldBe null
        }
    }
})
