package com.kinetix.position

import com.kinetix.position.model.LimitBreach
import com.kinetix.position.model.LimitBreachResult
import com.kinetix.position.model.LimitBreachSeverity
import com.kinetix.position.routes.preTradeCheckRoutes
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.PreTradeCheckService
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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class ErrorBody(val error: String, val message: String)

private fun Application.configureTestApp(preTradeCheckService: PreTradeCheckService) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", cause.message ?: ""))
        }
    }
    routing {
        preTradeCheckRoutes(preTradeCheckService)
    }
}

private val validRequest = """
    {
        "bookId": "book-1",
        "instrumentId": "AAPL",
        "assetClass": "EQUITY",
        "side": "BUY",
        "quantity": "100",
        "priceAmount": "150.00",
        "priceCurrency": "USD",
        "instrumentType": "CASH_EQUITY"
    }
""".trimIndent()

class PreTradeCheckAcceptanceTest : FunSpec({

    test("returns APPROVED when no limits are breached") {
        val checkService = mockk<PreTradeCheckService>()
        coEvery { checkService.check(any()) } returns LimitBreachResult(emptyList())

        testApplication {
            application { configureTestApp(checkService) }
            val response = client.post("/api/v1/risk/pre-trade-check") {
                contentType(ContentType.Application.Json)
                setBody(validRequest)
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["approved"]!!.jsonPrimitive.content shouldBe "true"
            body["result"]!!.jsonPrimitive.content shouldBe "APPROVED"
        }
    }

    test("returns REJECTED with breach details when a hard limit is exceeded") {
        val checkService = mockk<PreTradeCheckService>()
        coEvery { checkService.check(any()) } returns LimitBreachResult(
            listOf(
                LimitBreach(
                    limitType = "POSITION",
                    severity = LimitBreachSeverity.HARD,
                    currentValue = "1001",
                    limitValue = "1000",
                    message = "Position 1001 exceeds limit at BOOK level",
                )
            )
        )

        testApplication {
            application { configureTestApp(checkService) }
            val response = client.post("/api/v1/risk/pre-trade-check") {
                contentType(ContentType.Application.Json)
                setBody(validRequest)
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["approved"]!!.jsonPrimitive.content shouldBe "false"
            body["result"]!!.jsonPrimitive.content shouldBe "REJECTED"
            response.bodyAsText() shouldContain "POSITION"
        }
    }

    test("returns FLAGGED with warnings when only soft limits are approached") {
        val checkService = mockk<PreTradeCheckService>()
        coEvery { checkService.check(any()) } returns LimitBreachResult(
            listOf(
                LimitBreach(
                    limitType = "NOTIONAL",
                    severity = LimitBreachSeverity.SOFT,
                    currentValue = "85000",
                    limitValue = "100000",
                    message = "Portfolio notional 85000 approaching limit at BOOK level",
                )
            )
        )

        testApplication {
            application { configureTestApp(checkService) }
            val response = client.post("/api/v1/risk/pre-trade-check") {
                contentType(ContentType.Application.Json)
                setBody(validRequest)
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["approved"]!!.jsonPrimitive.content shouldBe "true"
            body["result"]!!.jsonPrimitive.content shouldBe "FLAGGED"
        }
    }

    test("returns 400 Bad Request when quantity is zero") {
        val checkService = mockk<PreTradeCheckService>()

        testApplication {
            application { configureTestApp(checkService) }
            val response = client.post("/api/v1/risk/pre-trade-check") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "bookId": "book-1",
                        "instrumentId": "AAPL",
                        "assetClass": "EQUITY",
                        "side": "BUY",
                        "quantity": "0",
                        "priceAmount": "150.00",
                        "priceCurrency": "USD",
                        "instrumentType": "CASH_EQUITY"
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("returns REJECTED with TIMEOUT breach when check service exceeds 100ms timeout") {
        val checkService = mockk<PreTradeCheckService>()
        coEvery { checkService.check(any()) } coAnswers {
            // Simulate a slow DB call that exceeds the 100ms budget
            kotlinx.coroutines.delay(200)
            LimitBreachResult(emptyList())
        }

        testApplication {
            application { configureTestApp(checkService) }
            val response = client.post("/api/v1/risk/pre-trade-check") {
                contentType(ContentType.Application.Json)
                setBody(validRequest)
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["approved"]!!.jsonPrimitive.content shouldBe "false"
            body["result"]!!.jsonPrimitive.content shouldBe "REJECTED"
            response.bodyAsText() shouldContain "timed out"
        }
    }

    test("pre-trade check does not persist any trade — idempotent calls return consistent results") {
        val checkService = mockk<PreTradeCheckService>()
        coEvery { checkService.check(any()) } returns LimitBreachResult(emptyList())

        testApplication {
            application { configureTestApp(checkService) }
            // Call the same endpoint three times — should not accumulate position state
            repeat(3) {
                val response = client.post("/api/v1/risk/pre-trade-check") {
                    contentType(ContentType.Application.Json)
                    setBody(validRequest)
                }
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["result"]!!.jsonPrimitive.content shouldBe "APPROVED"
            }
        }
    }
})
