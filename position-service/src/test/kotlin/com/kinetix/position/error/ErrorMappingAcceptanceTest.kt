package com.kinetix.position.error

import com.kinetix.common.model.TradeStatus
import com.kinetix.position.model.LimitBreach
import com.kinetix.position.model.LimitBreachResult
import com.kinetix.position.model.LimitBreachSeverity
import com.kinetix.position.service.InvalidTradeStateException
import com.kinetix.position.service.LimitBreachException
import com.kinetix.position.service.TradeNotFoundException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acceptance tests that pin the ApiError wire shape emitted by
 * position-service's StatusPages configuration.
 *
 * Every test installs only [configureErrorHandling] and a minimal route that
 * throws the target exception — no database, no Kafka, no real Application
 * module wiring. This keeps the test fast and the contract clear.
 */
class ErrorMappingAcceptanceTest : FunSpec({

    test("IllegalArgumentException returns 400 with BAD_REQUEST ApiError shape and a correlationId") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") { throw IllegalArgumentException("bad input value") }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "BAD_REQUEST"
            body["message"]?.jsonPrimitive?.content shouldBe "bad input value"
            body["correlationId"]?.jsonPrimitive?.content shouldNotBe null
        }
    }

    test("LimitBreachException returns 422 with LIMIT_BREACH ApiError shape") {
        val breach = LimitBreach(
            limitType = "POSITION",
            severity = LimitBreachSeverity.HARD,
            currentValue = "1500000",
            limitValue = "1000000",
            message = "Position limit exceeded",
        )
        val result = LimitBreachResult(breaches = listOf(breach))
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") { throw LimitBreachException(result) }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.UnprocessableEntity
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "LIMIT_BREACH"
            body["correlationId"]?.jsonPrimitive?.content shouldNotBe null
        }
    }

    test("TradeNotFoundException returns 404 with NOT_FOUND ApiError shape") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") { throw TradeNotFoundException("trade-42") }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.NotFound
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "NOT_FOUND"
            body["correlationId"]?.jsonPrimitive?.content shouldNotBe null
        }
    }

    test("InvalidTradeStateException returns 409 with CONFLICT ApiError shape") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") {
                        throw InvalidTradeStateException(
                            tradeId = "trade-7",
                            currentStatus = TradeStatus.CANCELLED,
                            attemptedAction = "amend",
                        )
                    }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.Conflict
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "CONFLICT"
            body["correlationId"]?.jsonPrimitive?.content shouldNotBe null
        }
    }

    test("unhandled Throwable returns 500 with INTERNAL_ERROR and suppresses the real message") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") { throw RuntimeException("sensitive internal detail") }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.InternalServerError
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "INTERNAL_ERROR"
            body["message"]?.jsonPrimitive?.content shouldBe "Internal server error"
            response.bodyAsText().contains("sensitive internal detail") shouldBe false
        }
    }
})
