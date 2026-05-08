package com.kinetix.position

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.Trade
import com.kinetix.common.model.TradeId
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.routes.counterpartyRoutes
import com.kinetix.position.service.CounterpartyExposureService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun Application.configureTestApp(service: CounterpartyExposureService) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "bad_request", "message" to (cause.message ?: "Invalid request")),
            )
        }
    }
    routing {
        counterpartyRoutes(service)
    }
}

private fun trade(
    id: String,
    bookId: String,
    counterpartyId: String?,
    side: Side = Side.BUY,
    quantity: String = "100",
    priceAmount: String = "150.00",
) = Trade(
    tradeId = TradeId(id),
    bookId = BookId(bookId),
    instrumentId = InstrumentId("AAPL"),
    assetClass = AssetClass.EQUITY,
    side = side,
    quantity = BigDecimal(quantity),
    price = Money(BigDecimal(priceAmount), USD),
    tradedAt = Instant.parse("2025-06-01T12:00:00Z"),
    counterpartyId = counterpartyId,
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class CounterpartyRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val tradeRepo = ExposedTradeEventRepository(db)
    val service = CounterpartyExposureService(tradeRepo, nettingSetTradeRepository = null)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE trade_events RESTART IDENTITY CASCADE")
        }
    }

    test("GET /api/v1/counterparty-exposure aggregates trades for the given book") {
        testApplication {
            application { configureTestApp(service) }

            // Two trades for CP-ALPHA in BOOK-001 (one BUY, one SELL — net = 0, gross = 30000)
            tradeRepo.save(trade("t-1", "BOOK-001", "CP-ALPHA", Side.BUY, "100", "150.00"))
            tradeRepo.save(trade("t-2", "BOOK-001", "CP-ALPHA", Side.SELL, "100", "150.00"))
            // One trade for CP-BETA in BOOK-001
            tradeRepo.save(trade("t-3", "BOOK-001", "CP-BETA", Side.BUY, "200", "100.00"))
            // Trade in a different book — must not appear
            tradeRepo.save(trade("t-4", "BOOK-002", "CP-ALPHA", Side.BUY, "50", "150.00"))
            // Trade with no counterparty — must be filtered out
            tradeRepo.save(trade("t-5", "BOOK-001", null, Side.BUY, "10", "100.00"))

            val response = client.get("/api/v1/counterparty-exposure?bookId=BOOK-001")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.map { it.jsonObject["counterpartyId"]!!.jsonPrimitive.content } shouldContainExactlyInAnyOrder
                listOf("CP-ALPHA", "CP-BETA")
        }
    }

    test("GET /api/v1/counterparty-exposure serialises BigDecimal exposure fields as strings") {
        testApplication {
            application { configureTestApp(service) }

            tradeRepo.save(trade("t-1", "BOOK-001", "CP-ALPHA", Side.BUY, "100", "150.00"))

            val response = client.get("/api/v1/counterparty-exposure?bookId=BOOK-001")

            response.status shouldBe HttpStatusCode.OK
            val element = Json.parseToJsonElement(response.bodyAsText()).jsonArray.first().jsonObject

            // netExposure and grossExposure must be JSON strings (not numbers) so that
            // BigDecimal precision is preserved across the wire.
            element["netExposure"]!!.jsonPrimitive.isString shouldBe true
            element["grossExposure"]!!.jsonPrimitive.isString shouldBe true
            element["netExposure"]!!.jsonPrimitive.content shouldBe "15000.00"
            element["grossExposure"]!!.jsonPrimitive.content shouldBe "15000.00"
            element["positionCount"]!!.jsonPrimitive.content shouldBe "1"
        }
    }

    test("GET /api/v1/counterparty-exposure returns 400 when bookId query parameter is missing") {
        testApplication {
            application { configureTestApp(service) }

            val response = client.get("/api/v1/counterparty-exposure")

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "bookId"
        }
    }

    test("GET /api/v1/counterparty-exposure returns an empty list for an unknown book") {
        testApplication {
            application { configureTestApp(service) }

            val response = client.get("/api/v1/counterparty-exposure?bookId=NO-SUCH-BOOK")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 0
        }
    }

    test("GET /api/v1/counterparties/{id}/trades returns trades for that counterparty") {
        testApplication {
            application { configureTestApp(service) }

            tradeRepo.save(trade("t-1", "BOOK-001", "CP-ALPHA"))
            tradeRepo.save(trade("t-2", "BOOK-001", "CP-ALPHA"))
            tradeRepo.save(trade("t-3", "BOOK-001", "CP-BETA"))

            val response = client.get("/api/v1/counterparties/CP-ALPHA/trades")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.map { it.jsonObject["tradeId"]!!.jsonPrimitive.content } shouldContainExactlyInAnyOrder
                listOf("t-1", "t-2")
        }
    }
})
