package com.kinetix.risk.error

import com.kinetix.common.resilience.CircuitBreakerOpenException
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
 * risk-orchestrator's StatusPages configuration.
 */
class ErrorMappingAcceptanceTest : FunSpec({

    test("IllegalArgumentException returns 400 with BAD_REQUEST ApiError shape and a correlationId") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") { throw IllegalArgumentException("bad argument") }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "BAD_REQUEST"
            body["message"]?.jsonPrimitive?.content shouldBe "bad argument"
            body["correlationId"]?.jsonPrimitive?.content shouldNotBe null
        }
    }

    test("CircuitBreakerOpenException returns 503 with SERVICE_UNAVAILABLE ApiError and Retry-After header") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") { throw CircuitBreakerOpenException("risk-engine") }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.headers["Retry-After"] shouldBe "30"
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "SERVICE_UNAVAILABLE"
            body["correlationId"]?.jsonPrimitive?.content shouldNotBe null
        }
    }

    test("gRPC DEADLINE_EXCEEDED returns 504 with UPSTREAM_TIMEOUT ApiError") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") {
                        throw io.grpc.StatusRuntimeException(io.grpc.Status.DEADLINE_EXCEEDED.withDescription("risk engine timed out"))
                    }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.GatewayTimeout
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "UPSTREAM_TIMEOUT"
            body["correlationId"]?.jsonPrimitive?.content shouldNotBe null
        }
    }

    test("gRPC INVALID_ARGUMENT returns 400 with BAD_REQUEST ApiError") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") {
                        throw io.grpc.StatusRuntimeException(io.grpc.Status.INVALID_ARGUMENT.withDescription("book not found"))
                    }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "BAD_REQUEST"
            body["correlationId"]?.jsonPrimitive?.content shouldNotBe null
        }
    }

    test("gRPC UNAVAILABLE returns 503 with SERVICE_UNAVAILABLE ApiError and Retry-After header") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") {
                        throw io.grpc.StatusRuntimeException(io.grpc.Status.UNAVAILABLE.withDescription("risk engine down"))
                    }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.headers["Retry-After"] shouldBe "5"
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "SERVICE_UNAVAILABLE"
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
