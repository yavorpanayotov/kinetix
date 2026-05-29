package com.kinetix.price.routes

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.PricePoint
import com.kinetix.common.model.PriceSource
import com.kinetix.common.model.Money
import com.kinetix.price.module
import com.kinetix.price.persistence.PriceRepository
import com.kinetix.price.service.PriceIngestionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun point(
    instrumentId: String = "AAPL",
    priceAmount: BigDecimal = BigDecimal("150.00"),
    timestamp: Instant = Instant.parse("2025-01-15T10:00:00Z"),
    source: PriceSource = PriceSource.EXCHANGE,
) = PricePoint(
    instrumentId = InstrumentId(instrumentId),
    price = Money(priceAmount, USD),
    timestamp = timestamp,
    source = source,
)

class PriceRoutesTest : FunSpec({

    val repository = mockk<PriceRepository>()
    val ingestionService = mockk<PriceIngestionService>()

    beforeTest { clearMocks(repository, ingestionService) }

    test("GET /api/v1/prices/{id}/latest returns 200 with price point") {
        val p = point()

        coEvery { repository.findLatest(InstrumentId("AAPL")) } returns p

        testApplication {
            application { module(repository, ingestionService) }

            val response = client.get("/api/v1/prices/AAPL/latest")

            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["instrumentId"]!!.jsonPrimitive.content shouldBe "AAPL"
            body["price"]!!.jsonObject["amount"]!!.jsonPrimitive.content shouldBe "150.00"
            body["price"]!!.jsonObject["currency"]!!.jsonPrimitive.content shouldBe "USD"
            body["timestamp"]!!.jsonPrimitive.content shouldBe "2025-01-15T10:00:00Z"
            body["source"]!!.jsonPrimitive.content shouldBe "EXCHANGE"
        }
    }

    test("GET /api/v1/prices/{id}/latest returns 404 for unknown instrument") {
        coEvery { repository.findLatest(InstrumentId("UNKNOWN")) } returns null

        testApplication {
            application { module(repository, ingestionService) }

            val response = client.get("/api/v1/prices/UNKNOWN/latest")

            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /api/v1/prices/{id}/history returns 200 with price history") {
        val from = Instant.parse("2025-01-15T09:00:00Z")
        val to = Instant.parse("2025-01-15T11:00:00Z")
        val points = listOf(
            point(timestamp = Instant.parse("2025-01-15T09:30:00Z"), priceAmount = BigDecimal("149.50")),
            point(timestamp = Instant.parse("2025-01-15T10:00:00Z"), priceAmount = BigDecimal("150.00")),
            point(timestamp = Instant.parse("2025-01-15T10:30:00Z"), priceAmount = BigDecimal("150.75")),
        )

        coEvery { repository.findByInstrumentId(InstrumentId("AAPL"), from, to) } returns points

        testApplication {
            application { module(repository, ingestionService) }

            val response = client.get("/api/v1/prices/AAPL/history?from=2025-01-15T09:00:00Z&to=2025-01-15T11:00:00Z")

            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 3

            val first = body[0].jsonObject
            first["instrumentId"]!!.jsonPrimitive.content shouldBe "AAPL"
            first["price"]!!.jsonObject["amount"]!!.jsonPrimitive.content shouldBe "149.50"
            first["timestamp"]!!.jsonPrimitive.content shouldBe "2025-01-15T09:30:00Z"
            first["source"]!!.jsonPrimitive.content shouldBe "EXCHANGE"

            val last = body[2].jsonObject
            last["price"]!!.jsonObject["amount"]!!.jsonPrimitive.content shouldBe "150.75"
            last["timestamp"]!!.jsonPrimitive.content shouldBe "2025-01-15T10:30:00Z"
        }
    }

    test("GET /api/v1/prices/{id}/history with interval=1d calls findDailyCloseByInstrumentId") {
        val from = Instant.parse("2025-01-15T09:00:00Z")
        val to = Instant.parse("2025-01-15T11:00:00Z")
        val points = listOf(
            point(timestamp = Instant.parse("2025-01-15T10:30:00Z"), priceAmount = BigDecimal("150.75")),
        )

        coEvery { repository.findDailyCloseByInstrumentId(InstrumentId("AAPL"), from, to) } returns points

        testApplication {
            application { module(repository, ingestionService) }

            val response = client.get("/api/v1/prices/AAPL/history?from=2025-01-15T09:00:00Z&to=2025-01-15T11:00:00Z&interval=1d")

            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 1
            body[0].jsonObject["price"]!!.jsonObject["amount"]!!.jsonPrimitive.content shouldBe "150.75"
        }

        coVerify(exactly = 0) { repository.findByInstrumentId(any(), any(), any()) }
        coVerify(exactly = 1) { repository.findDailyCloseByInstrumentId(InstrumentId("AAPL"), from, to) }
    }

    test("GET /api/v1/prices/{id}/history without interval calls findByInstrumentId") {
        val from = Instant.parse("2025-01-15T09:00:00Z")
        val to = Instant.parse("2025-01-15T11:00:00Z")

        coEvery { repository.findByInstrumentId(InstrumentId("AAPL"), from, to) } returns emptyList()

        testApplication {
            application { module(repository, ingestionService) }

            val response = client.get("/api/v1/prices/AAPL/history?from=2025-01-15T09:00:00Z&to=2025-01-15T11:00:00Z")

            response.status shouldBe HttpStatusCode.OK
        }

        coVerify(exactly = 1) { repository.findByInstrumentId(InstrumentId("AAPL"), from, to) }
        coVerify(exactly = 0) { repository.findDailyCloseByInstrumentId(any(), any(), any()) }
    }

    test("GET /api/v1/prices/{id}/history with unsupported interval returns 400") {
        testApplication {
            application { module(repository, ingestionService) }

            val response = client.get("/api/v1/prices/AAPL/history?from=2025-01-15T09:00:00Z&to=2025-01-15T11:00:00Z&interval=5m")

            response.status shouldBe HttpStatusCode.BadRequest

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]!!.jsonPrimitive.content shouldBe "BAD_REQUEST"
            body["message"]!!.jsonPrimitive.content shouldBe "Unsupported interval: 5m. Supported values: 1d"
        }
    }

    test("POST /api/v1/prices/ingest returns 400 for negative price amount") {
        testApplication {
            application { module(repository, ingestionService) }

            val response = client.post("/api/v1/prices/ingest") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"AAPL","priceAmount":"-1.00","priceCurrency":"USD","source":"EXCHANGE"}""")
            }

            response.status shouldBe HttpStatusCode.BadRequest

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]!!.jsonPrimitive.content shouldBe "BAD_REQUEST"
            body["message"]!!.jsonPrimitive.content shouldBe "priceAmount must be non-negative, was -1.00"
        }

        coVerify(exactly = 0) { ingestionService.ingest(any()) }
    }

    test("POST /api/v1/prices/ingest returns 400 for blank instrumentId") {
        testApplication {
            application { module(repository, ingestionService) }

            val response = client.post("/api/v1/prices/ingest") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"","priceAmount":"1.00","priceCurrency":"USD","source":"EXCHANGE"}""")
            }

            response.status shouldBe HttpStatusCode.BadRequest

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]!!.jsonPrimitive.content shouldBe "BAD_REQUEST"
            body["message"]!!.jsonPrimitive.content shouldBe "instrumentId must not be blank"
        }

        coVerify(exactly = 0) { ingestionService.ingest(any()) }
    }

    test("POST /api/v1/prices/ingest accepts price amount of zero") {
        coEvery { ingestionService.ingest(any()) } returns Unit

        testApplication {
            application { module(repository, ingestionService) }

            val response = client.post("/api/v1/prices/ingest") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"AAPL","priceAmount":"0","priceCurrency":"USD","source":"EXCHANGE"}""")
            }

            response.status shouldBe HttpStatusCode.Created
        }

        coVerify(exactly = 1) { ingestionService.ingest(any()) }
    }

    test("GET /api/v1/prices/{id}/history returns 400 for missing query params") {
        testApplication {
            application { module(repository, ingestionService) }

            val noParams = client.get("/api/v1/prices/AAPL/history")
            noParams.status shouldBe HttpStatusCode.BadRequest
            val noParamsBody = Json.parseToJsonElement(noParams.bodyAsText()).jsonObject
            noParamsBody["code"]!!.jsonPrimitive.content shouldBe "BAD_REQUEST"
            noParamsBody["message"]!!.jsonPrimitive.content shouldBe "Missing required query parameter: from"

            val onlyFrom = client.get("/api/v1/prices/AAPL/history?from=2025-01-15T09:00:00Z")
            onlyFrom.status shouldBe HttpStatusCode.BadRequest
            val onlyFromBody = Json.parseToJsonElement(onlyFrom.bodyAsText()).jsonObject
            onlyFromBody["code"]!!.jsonPrimitive.content shouldBe "BAD_REQUEST"
            onlyFromBody["message"]!!.jsonPrimitive.content shouldBe "Missing required query parameter: to"
        }
    }
})
