package com.kinetix.risk.routes

import com.kinetix.common.resilience.CircuitBreakerOpenException
import com.kinetix.risk.error.configureErrorHandling
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

class RiskRoutesErrorHandlingTest : FunSpec({

    test("returns 503 with Retry-After when circuit breaker is open") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/test") { throw CircuitBreakerOpenException("risk-engine") }
                }
            }
            val response = client.get("/test")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.headers["Retry-After"] shouldBe "30"
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "SERVICE_UNAVAILABLE"
        }
    }

    test("returns 504 on gRPC DEADLINE_EXCEEDED") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/test") { throw StatusRuntimeException(Status.DEADLINE_EXCEEDED) }
                }
            }
            val response = client.get("/test")
            response.status shouldBe HttpStatusCode.GatewayTimeout
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "UPSTREAM_TIMEOUT"
        }
    }

    test("returns 400 on gRPC INVALID_ARGUMENT") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/test") {
                        throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("empty positions"))
                    }
                }
            }
            val response = client.get("/test")
            response.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "BAD_REQUEST"
            body["message"]?.jsonPrimitive?.content shouldContain "empty positions"
        }
    }

    test("returns 503 with Retry-After on gRPC UNAVAILABLE") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/test") {
                        throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("connection refused"))
                    }
                }
            }
            val response = client.get("/test")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.headers["Retry-After"] shouldBe "5"
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "SERVICE_UNAVAILABLE"
            body["message"]?.jsonPrimitive?.content shouldContain "connection refused"
        }
    }

    test("returns 503 with Retry-After on gRPC RESOURCE_EXHAUSTED") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/test") {
                        throw StatusRuntimeException(Status.RESOURCE_EXHAUSTED.withDescription("too many requests"))
                    }
                }
            }
            val response = client.get("/test")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.headers["Retry-After"] shouldBe "5"
        }
    }

    test("returns 502 with error message on gRPC INTERNAL") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/test") {
                        throw StatusRuntimeException(Status.INTERNAL.withDescription("numpy error"))
                    }
                }
            }
            val response = client.get("/test")
            response.status shouldBe HttpStatusCode.BadGateway
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "BAD_GATEWAY"
            body["message"]?.jsonPrimitive?.content shouldContain "numpy error"
        }
    }
})
