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
import com.kinetix.position.routes.TradeCountResponse
import com.kinetix.position.routes.internalRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun Application.configureTestApp(tradeRepo: ExposedTradeEventRepository) {
    install(ContentNegotiation) { json() }
    routing {
        internalRoutes(tradeRepo)
    }
}

private fun sampleTrade(id: String, tradedAt: Instant) = Trade(
    tradeId = TradeId(id),
    bookId = BookId("BOOK-001"),
    instrumentId = InstrumentId("AAPL"),
    assetClass = AssetClass.EQUITY,
    side = Side.BUY,
    quantity = BigDecimal("100"),
    price = Money(BigDecimal("150.00"), USD),
    tradedAt = tradedAt,
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class InternalRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val tradeRepo = ExposedTradeEventRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE trade_events RESTART IDENTITY CASCADE")
        }
    }

    test("GET /api/v1/internal/trades/count returns 400 when the since parameter is missing") {
        testApplication {
            application { configureTestApp(tradeRepo) }

            val response = client.get("/api/v1/internal/trades/count")

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "since"
        }
    }

    test("GET /api/v1/internal/trades/count returns 400 when the since parameter is malformed") {
        testApplication {
            application { configureTestApp(tradeRepo) }

            val response = client.get("/api/v1/internal/trades/count?since=not-a-date")

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid timestamp format"
        }
    }

    test("GET /api/v1/internal/trades/count returns the count of trades persisted since the given timestamp") {
        testApplication {
            application { configureTestApp(tradeRepo) }

            // Trades persisted before the marker should not be counted.
            tradeRepo.save(sampleTrade("t-old-1", Instant.parse("2025-05-01T10:00:00Z")))
            tradeRepo.save(sampleTrade("t-old-2", Instant.parse("2025-05-01T11:00:00Z")))

            // Capture the marker and then persist three more. countSince filters on
            // the row's createdAt (insertion time), so all pre-marker rows are excluded.
            val sinceMarker = Instant.now().plusSeconds(1)
            Thread.sleep(1100)

            tradeRepo.save(sampleTrade("t-new-1", Instant.parse("2025-06-01T12:00:00Z")))
            tradeRepo.save(sampleTrade("t-new-2", Instant.parse("2025-06-01T12:01:00Z")))
            tradeRepo.save(sampleTrade("t-new-3", Instant.parse("2025-06-01T12:02:00Z")))

            val response = client.get("/api/v1/internal/trades/count?since=$sinceMarker")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<TradeCountResponse>(response.bodyAsText())
            body.count shouldBe 3L
        }
    }

    test("GET /api/v1/internal/trades/count returns zero for a future timestamp") {
        testApplication {
            application { configureTestApp(tradeRepo) }

            tradeRepo.save(sampleTrade("t-1", Instant.parse("2025-06-01T12:00:00Z")))

            val response = client.get("/api/v1/internal/trades/count?since=2099-01-01T00:00:00Z")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<TradeCountResponse>(response.bodyAsText())
            body.count shouldBe 0L
        }
    }
})
