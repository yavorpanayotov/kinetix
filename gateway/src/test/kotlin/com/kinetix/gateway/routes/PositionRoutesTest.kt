package com.kinetix.gateway.routes

import com.kinetix.common.model.*
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.gateway.client.BookTradeCommand
import com.kinetix.gateway.client.BookTradeResult
import com.kinetix.gateway.client.InstrumentServiceClient
import com.kinetix.gateway.client.InstrumentSummary
import com.kinetix.gateway.client.PortfolioSummary
import com.kinetix.gateway.client.PositionServiceClient
import com.kinetix.gateway.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private fun usd(amount: String) = Money(BigDecimal(amount), USD)

class PositionRoutesTest : FunSpec({

    val positionClient = mockk<PositionServiceClient>()

    beforeEach { clearMocks(positionClient) }

    // --- GET /api/v1/books ---

    test("GET /api/v1/books returns 200 with portfolio list") {
        coEvery { positionClient.listPortfolios() } returns listOf(
            PortfolioSummary(BookId("port-1")),
            PortfolioSummary(BookId("port-2")),
        )

        testApplication {
            application { module(positionClient) }
            val response = client.get("/api/v1/books")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 2
            body[0].jsonObject["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
            body[1].jsonObject["bookId"]?.jsonPrimitive?.content shouldBe "port-2"
        }
    }

    test("GET /api/v1/books returns empty array when no portfolios") {
        coEvery { positionClient.listPortfolios() } returns emptyList()

        testApplication {
            application { module(positionClient) }
            val response = client.get("/api/v1/books")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "[]"
        }
    }

    // --- POST /api/v1/books/{bookId}/trades ---

    test("POST /trades returns 201 Created with trade and position") {
        val trade = Trade(
            tradeId = TradeId("t-1"),
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("100"),
            price = usd("150.00"),
            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = usd("150.00"),
            marketPrice = usd("155.00"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionClient.bookTrade(any()) } returns BookTradeResult(trade, position)

        testApplication {
            application { module(positionClient) }
            val response = client.post("/api/v1/books/port-1/trades") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tradeId": "t-1",
                        "instrumentId": "AAPL",
                        "assetClass": "EQUITY",
                        "side": "BUY",
                        "quantity": "100",
                        "priceAmount": "150.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["trade"]!!.jsonObject["tradeId"]?.jsonPrimitive?.content shouldBe "t-1"
            body["trade"]!!.jsonObject["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
            body["position"]!!.jsonObject["quantity"]?.jsonPrimitive?.content shouldBe "100"
        }
    }

    test("POST /trades passes bookId from path to command") {
        val commandSlot = slot<BookTradeCommand>()
        val trade = Trade(
            tradeId = TradeId("t-1"),
            bookId = BookId("my-port"),
            instrumentId = InstrumentId("MSFT"),
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("50"),
            price = usd("300.00"),
            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val position = Position(
            bookId = BookId("my-port"),
            instrumentId = InstrumentId("MSFT"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("50"),
            averageCost = usd("300.00"),
            marketPrice = usd("300.00"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionClient.bookTrade(capture(commandSlot)) } returns BookTradeResult(trade, position)

        testApplication {
            application { module(positionClient) }
            client.post("/api/v1/books/my-port/trades") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tradeId": "t-1",
                        "instrumentId": "MSFT",
                        "assetClass": "EQUITY",
                        "side": "BUY",
                        "quantity": "50",
                        "priceAmount": "300.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}
                """.trimIndent())
            }
            commandSlot.captured.bookId shouldBe BookId("my-port")
        }
    }

    test("POST /trades returns 400 for invalid assetClass") {
        testApplication {
            application { module(positionClient) }
            val response = client.post("/api/v1/books/port-1/trades") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tradeId": "t-1",
                        "instrumentId": "AAPL",
                        "assetClass": "INVALID",
                        "side": "BUY",
                        "quantity": "100",
                        "priceAmount": "150.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST /trades returns 400 for negative quantity") {
        testApplication {
            application { module(positionClient) }
            val response = client.post("/api/v1/books/port-1/trades") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tradeId": "t-1",
                        "instrumentId": "AAPL",
                        "assetClass": "EQUITY",
                        "side": "BUY",
                        "quantity": "-100",
                        "priceAmount": "150.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST /trades returns 400 for blank tradeId") {
        testApplication {
            application { module(positionClient) }
            val response = client.post("/api/v1/books/port-1/trades") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tradeId": " ",
                        "instrumentId": "AAPL",
                        "assetClass": "EQUITY",
                        "side": "BUY",
                        "quantity": "100",
                        "priceAmount": "150.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST /trades response includes computed position fields") {
        val trade = Trade(
            tradeId = TradeId("t-1"),
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("100"),
            price = usd("150.00"),
            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = usd("150.00"),
            marketPrice = usd("155.00"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionClient.bookTrade(any()) } returns BookTradeResult(trade, position)

        testApplication {
            application { module(positionClient) }
            val response = client.post("/api/v1/books/port-1/trades") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tradeId": "t-1",
                        "instrumentId": "AAPL",
                        "assetClass": "EQUITY",
                        "side": "BUY",
                        "quantity": "100",
                        "priceAmount": "150.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}
                """.trimIndent())
            }
            val posObj = Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["position"]!!.jsonObject
            posObj["marketValue"]!!.jsonObject["amount"]?.jsonPrimitive?.content shouldBe "15500.00"
            posObj["unrealizedPnl"]!!.jsonObject["amount"]?.jsonPrimitive?.content shouldBe "500.00"
        }
    }

    // --- GET /api/v1/books/{bookId}/trades ---

    test("GET /trades returns 200 with trade history") {
        val trades = listOf(
            Trade(
                tradeId = TradeId("t-1"),
                bookId = BookId("port-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = usd("150.00"),
                tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
            Trade(
                tradeId = TradeId("t-2"),
                bookId = BookId("port-1"),
                instrumentId = InstrumentId("MSFT"),
                assetClass = AssetClass.EQUITY,
                side = Side.SELL,
                quantity = BigDecimal("50"),
                price = usd("300.00"),
                tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        coEvery { positionClient.getTradeHistory(BookId("port-1")) } returns trades

        testApplication {
            application { module(positionClient) }
            val response = client.get("/api/v1/books/port-1/trades")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 2
            body[0].jsonObject["tradeId"]?.jsonPrimitive?.content shouldBe "t-1"
            body[0].jsonObject["side"]?.jsonPrimitive?.content shouldBe "BUY"
            body[1].jsonObject["tradeId"]?.jsonPrimitive?.content shouldBe "t-2"
            body[1].jsonObject["side"]?.jsonPrimitive?.content shouldBe "SELL"
        }
    }

    test("GET /trades returns empty array for portfolio with no trades") {
        coEvery { positionClient.getTradeHistory(BookId("port-1")) } returns emptyList()

        testApplication {
            application { module(positionClient) }
            val response = client.get("/api/v1/books/port-1/trades")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "[]"
        }
    }

    test("GET /trades response includes status field reflecting trade lifecycle state") {
        val liveTrade = Trade(
            tradeId = TradeId("t-live"),
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("100"),
            price = usd("150.00"),
            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
            status = TradeStatus.LIVE,
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val cancelledTrade = Trade(
            tradeId = TradeId("t-cancelled"),
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("MSFT"),
            assetClass = AssetClass.EQUITY,
            side = Side.SELL,
            quantity = BigDecimal("50"),
            price = usd("300.00"),
            tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
            status = TradeStatus.CANCELLED,
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionClient.getTradeHistory(BookId("port-1")) } returns listOf(liveTrade, cancelledTrade)

        testApplication {
            application { module(positionClient) }
            val response = client.get("/api/v1/books/port-1/trades")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body[0].jsonObject["status"]?.jsonPrimitive?.content shouldBe "LIVE"
            body[1].jsonObject["status"]?.jsonPrimitive?.content shouldBe "CANCELLED"
        }
    }

    // --- GET /api/v1/books/{bookId}/positions ---

    test("GET /positions returns 200 with position list") {
        val positions = listOf(
            Position(
                bookId = BookId("port-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = usd("150.00"),
                marketPrice = usd("155.00"),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
            Position(
                bookId = BookId("port-1"),
                instrumentId = InstrumentId("MSFT"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("50"),
                averageCost = usd("300.00"),
                marketPrice = usd("310.00"),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        coEvery { positionClient.getPositions(BookId("port-1")) } returns positions

        testApplication {
            application { module(positionClient) }
            val response = client.get("/api/v1/books/port-1/positions")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 2
            body[0].jsonObject["instrumentId"]?.jsonPrimitive?.content shouldBe "AAPL"
            body[0].jsonObject["quantity"]?.jsonPrimitive?.content shouldBe "100"
            body[1].jsonObject["instrumentId"]?.jsonPrimitive?.content shouldBe "MSFT"
        }
    }

    test("GET /positions returns empty array for unknown portfolio") {
        coEvery { positionClient.getPositions(BookId("unknown")) } returns emptyList()

        testApplication {
            application { module(positionClient) }
            val response = client.get("/api/v1/books/unknown/positions")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "[]"
        }
    }

    test("GET /positions includes computed marketValue and unrealizedPnl") {
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = usd("50.00"),
            marketPrice = usd("55.00"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionClient.getPositions(BookId("port-1")) } returns listOf(position)

        testApplication {
            application { module(positionClient) }
            val response = client.get("/api/v1/books/port-1/positions")
            val posObj = Json.parseToJsonElement(response.bodyAsText()).jsonArray[0].jsonObject
            posObj["marketValue"]!!.jsonObject["amount"]?.jsonPrimitive?.content shouldBe "5500.00"
            posObj["unrealizedPnl"]!!.jsonObject["amount"]?.jsonPrimitive?.content shouldBe "500.00"
            posObj["marketValue"]!!.jsonObject["currency"]?.jsonPrimitive?.content shouldBe "USD"
        }
    }

    // --- Error handling ---

    test("error response body contains error and message fields") {
        testApplication {
            application { module(positionClient) }
            val response = client.post("/api/v1/books/port-1/trades") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tradeId": "t-1",
                        "instrumentId": "AAPL",
                        "assetClass": "NOT_REAL",
                        "side": "BUY",
                        "quantity": "100",
                        "priceAmount": "150.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body.containsKey("error") shouldBe true
            body.containsKey("message") shouldBe true
        }
    }

    // --- Regression ---

    test("GET /health still returns 200 when position routes are installed") {
        testApplication {
            application { module(positionClient) }
            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    // --- Instrument enrichment ---

    test("GET /positions enriches positions with displayName and instrumentType from instrument service") {
        val instrumentClient = mockk<InstrumentServiceClient>()
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = usd("150.00"),
            marketPrice = usd("155.00"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionClient.getPositions(BookId("port-1")) } returns listOf(position)
        coEvery { instrumentClient.fetchAll() } returns listOf(
            InstrumentSummary("AAPL", "CASH_EQUITY", "Apple Inc."),
        )

        testApplication {
            application { module(positionClient, instrumentClient) }
            val response = client.get("/api/v1/books/port-1/positions")
            response.status shouldBe HttpStatusCode.OK
            val posObj = Json.parseToJsonElement(response.bodyAsText()).jsonArray[0].jsonObject
            posObj["displayName"]?.jsonPrimitive?.content shouldBe "Apple Inc."
            posObj["instrumentType"]?.jsonPrimitive?.content shouldBe "CASH_EQUITY"
        }
    }

    test("GET /positions falls back to position instrumentType when instrument not in reference data") {
        val instrumentClient = mockk<InstrumentServiceClient>()
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("CUSTOM-1"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("50"),
            averageCost = usd("100.00"),
            marketPrice = usd("100.00"),
            instrumentType = InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionClient.getPositions(BookId("port-1")) } returns listOf(position)
        coEvery { instrumentClient.fetchAll() } returns emptyList()

        testApplication {
            application { module(positionClient, instrumentClient) }
            val response = client.get("/api/v1/books/port-1/positions")
            val posObj = Json.parseToJsonElement(response.bodyAsText()).jsonArray[0].jsonObject
            posObj["instrumentType"]?.jsonPrimitive?.content shouldBe "CASH_EQUITY"
            posObj["displayName"]?.jsonPrimitive?.contentOrNull shouldBe null
        }
    }

    test("POST /trades passes instrumentType through to position service") {
        val commandSlot = slot<BookTradeCommand>()
        val trade = Trade(
            tradeId = TradeId("t-1"),
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("100"),
            price = usd("150.00"),
            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = usd("150.00"),
            marketPrice = usd("150.00"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionClient.bookTrade(capture(commandSlot)) } returns BookTradeResult(trade, position)

        testApplication {
            application { module(positionClient) }
            client.post("/api/v1/books/port-1/trades") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tradeId": "t-1",
                        "instrumentId": "AAPL",
                        "assetClass": "EQUITY",
                        "side": "BUY",
                        "quantity": "100",
                        "priceAmount": "150.00",
                        "priceCurrency": "USD",
                        "tradedAt": "2025-01-15T10:00:00Z",
                        "instrumentType": "CASH_EQUITY"
                    }
                """.trimIndent())
            }
            commandSlot.captured.instrumentType shouldBe "CASH_EQUITY"
        }
    }
})
