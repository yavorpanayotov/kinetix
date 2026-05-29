package com.kinetix.gateway.acceptance

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acceptance tests for the gateway's error-mapping contract.
 *
 * Pins the `ApiError` wire shape (`code`, `message`, `correlationId`) that
 * StatusPages emits for every recognised exception type. Also verifies that
 * generic `RuntimeException` messages do NOT leak through — callers see
 * "Internal server error" rather than the raw cause text.
 */
class ErrorMappingAcceptanceTest : FunSpec({

    test("endpoint throws UpstreamErrorException(502) — response is 502 with ApiError code UPSTREAM_ERROR") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application {
                    module(riskClient)
                    routing {
                        get("/test/upstream-error") {
                            throw com.kinetix.gateway.client.UpstreamErrorException(502, "Bad gateway from upstream")
                        }
                    }
                }
                val response = client.get("/test/upstream-error") {
                    header("X-Correlation-ID", "corr-abc-123")
                }
                response.status shouldBe HttpStatusCode.BadGateway
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["code"]?.jsonPrimitive?.content shouldBe "UPSTREAM_ERROR"
                body["message"]?.jsonPrimitive?.content shouldBe "Bad gateway from upstream"
                body["correlationId"]?.jsonPrimitive?.content.shouldNotBeNull()
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("endpoint throws ServiceUnavailableException — response is 503 with Retry-After header and ApiError code SERVICE_UNAVAILABLE") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application {
                    module(riskClient)
                    routing {
                        get("/test/service-unavailable") {
                            throw com.kinetix.gateway.client.ServiceUnavailableException(30, "Risk engine is down")
                        }
                    }
                }
                val response = client.get("/test/service-unavailable")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.headers[HttpHeaders.RetryAfter] shouldBe "30"
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["code"]?.jsonPrimitive?.content shouldBe "SERVICE_UNAVAILABLE"
                body["message"]?.jsonPrimitive?.content shouldBe "Risk engine is down"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("endpoint throws GatewayTimeoutException — response is 504 with ApiError code GATEWAY_TIMEOUT") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application {
                    module(riskClient)
                    routing {
                        get("/test/gateway-timeout") {
                            throw com.kinetix.gateway.client.GatewayTimeoutException("Upstream timed out after 5s")
                        }
                    }
                }
                val response = client.get("/test/gateway-timeout")
                response.status shouldBe HttpStatusCode.GatewayTimeout
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["code"]?.jsonPrimitive?.content shouldBe "GATEWAY_TIMEOUT"
                body["message"]?.jsonPrimitive?.content shouldBe "Upstream timed out after 5s"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("endpoint throws IllegalArgumentException — response is 400 with ApiError code BAD_REQUEST containing the argument message") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application {
                    module(riskClient)
                    routing {
                        get("/test/bad-request") {
                            throw IllegalArgumentException("missing bookId")
                        }
                    }
                }
                val response = client.get("/test/bad-request")
                response.status shouldBe HttpStatusCode.BadRequest
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["code"]?.jsonPrimitive?.content shouldBe "BAD_REQUEST"
                body["message"]?.jsonPrimitive?.content shouldBe "missing bookId"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("endpoint throws RuntimeException — response is 500 with code INTERNAL_ERROR and message does NOT leak cause text") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application {
                    module(riskClient)
                    routing {
                        get("/test/internal-error") {
                            throw RuntimeException("kaboom — secret internal details")
                        }
                    }
                }
                val response = client.get("/test/internal-error")
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["code"]?.jsonPrimitive?.content shouldBe "INTERNAL_ERROR"
                val message = body["message"]?.jsonPrimitive?.content ?: ""
                // The raw cause message must NOT appear in the response body — it
                // could contain sensitive internal details (SQL, class names, etc.).
                message shouldNotContain "kaboom"
                message shouldNotContain "secret internal details"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
