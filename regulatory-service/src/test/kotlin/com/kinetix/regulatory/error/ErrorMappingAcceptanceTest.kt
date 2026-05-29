package com.kinetix.regulatory.error

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
 * regulatory-service's StatusPages configuration.
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

    test("NoSuchElementException returns 404 with NOT_FOUND ApiError shape") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") { throw NoSuchElementException("resource not found") }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.NotFound
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "NOT_FOUND"
            body["message"]?.jsonPrimitive?.content shouldBe "resource not found"
            body["correlationId"]?.jsonPrimitive?.content shouldNotBe null
        }
    }

    test("IllegalStateException returns 400 with BAD_REQUEST ApiError shape") {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureErrorHandling()
                routing {
                    get("/throw") { throw IllegalStateException("invalid state transition") }
                }
            }
            val response = client.get("/throw")
            response.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["code"]?.jsonPrimitive?.content shouldBe "BAD_REQUEST"
            body["message"]?.jsonPrimitive?.content shouldBe "invalid state transition"
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
