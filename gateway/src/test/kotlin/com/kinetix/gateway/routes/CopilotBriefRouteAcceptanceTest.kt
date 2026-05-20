package com.kinetix.gateway.routes

import com.kinetix.gateway.moduleWithInsightsProxy
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

/**
 * Acceptance test for the gateway's `GET /api/v1/insights/brief/today` proxy —
 * the Copilot "daily brief" surface registered on top of the buffered
 * [proxyToInsights]. The gateway is a thin pass-through to `ai-insights-service`:
 * it must return the upstream response body verbatim (the brief schema, including
 * the `mode` field, is owned by the ai-insights-service, not the gateway) and
 * propagate the upstream status — both the 200 "ready" response and the 202
 * "still generating" response.
 *
 * A real embedded Netty server plays the role of `ai-insights-service` on a
 * random port so the proxy travels over real HTTP — content negotiation,
 * serialisation, and HTTP wire behaviour are all exercised (per CLAUDE.md
 * "Project Conventions": acceptance tests use real HTTP for HTTP-only fakes).
 */
class CopilotBriefRouteAcceptanceTest : FunSpec({

    test("GET /api/v1/insights/brief/today proxies the upstream brief response verbatim") {
        // Compact JSON, exactly as the real ai-insights-service brief endpoint
        // serialises it — the gateway must return the bytes untouched.
        val cannedUpstreamResponse =
            """{"status":"ready","briefs":[""" +
                """{"headline":"Tech equity beta lifts portfolio VaR",""" +
                """"body":"Long AAPL and MSFT exposure drove a 12% VaR increase overnight."},""" +
                """{"headline":"Regime shift to high volatility",""" +
                """"body":"The market regime classifier flipped to high_vol at the open."}],""" +
                """"mode":"canned","generated_at":"2026-05-20T06:00:00Z"}"""

        val backend = BackendStubServer {
            get("/api/v1/insights/brief/today") {
                call.respondText(cannedUpstreamResponse, ContentType.Application.Json)
            }
        }

        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl) }

                val response = client.get("/api/v1/insights/brief/today")

                // Status pass-through
                response.status shouldBe HttpStatusCode.OK

                // Response body returned to caller verbatim — byte-for-byte.
                val body = response.bodyAsText()
                body shouldBe cannedUpstreamResponse

                // The `mode` field survives the proxy untouched.
                body shouldContain "\"mode\":\"canned\""
                Json.parseToJsonElement(body).jsonObject["mode"]?.jsonPrimitive?.content shouldBe
                    "canned"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/insights/brief/today preserves application/json content-type") {
        val backend = BackendStubServer {
            get("/api/v1/insights/brief/today") {
                call.respondText(
                    """{"status":"ready","briefs":[],"mode":"canned","generated_at":"2026-05-20T06:00:00Z"}""",
                    ContentType.Application.Json,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl) }

                val response = client.get("/api/v1/insights/brief/today")

                response.status shouldBe HttpStatusCode.OK
                val responseCt = response.contentType()?.toString() ?: ""
                responseCt shouldStartWith "application/json"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/insights/brief/today passes through a 202 generating response") {
        val generatingBody = """{"status":"generating","retry_after":5}"""
        val backend = BackendStubServer {
            get("/api/v1/insights/brief/today") {
                call.respondText(
                    generatingBody,
                    ContentType.Application.Json,
                    HttpStatusCode.Accepted,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl) }

                val response = client.get("/api/v1/insights/brief/today")

                response.status shouldBe HttpStatusCode.Accepted
                Json.parseToJsonElement(response.bodyAsText()) shouldBe
                    Json.parseToJsonElement(generatingBody)
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/insights/brief/today forwards request headers to ai-insights-service") {
        val capturedUserId = AtomicReference<String?>(null)
        val backend = BackendStubServer {
            get("/api/v1/insights/brief/today") {
                capturedUserId.set(call.request.headers["X-User-Id"])
                call.respondText(
                    """{"status":"ready","briefs":[],"mode":"canned","generated_at":"2026-05-20T06:00:00Z"}""",
                    ContentType.Application.Json,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl) }

                val response = client.get("/api/v1/insights/brief/today") {
                    header("X-User-Id", "trader-1")
                }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText()
                capturedUserId.get() shouldBe "trader-1"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/insights/brief/today propagates a non-2xx upstream status") {
        val backend = BackendStubServer {
            get("/api/v1/insights/brief/today") {
                call.respondText(
                    """{"error":"ai-insights-service unavailable"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl) }

                val response = client.get("/api/v1/insights/brief/today")

                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.bodyAsText() shouldContain "ai-insights-service unavailable"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
