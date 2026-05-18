package com.kinetix.gateway.routes

import com.kinetix.gateway.moduleWithInsightsProxy
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * Acceptance test for the gateway's `POST /api/v1/insights/explain/var` and
 * `POST /api/v1/insights/explain/report` proxies. The gateway is a thin
 * pass-through to `ai-insights-service`: it must forward the request body
 * verbatim and return the upstream response body verbatim. We stand up a
 * real embedded Netty server playing the role of `ai-insights-service` on
 * a random port so the proxy travels over real HTTP — content negotiation,
 * serialisation, and HTTP wire behaviour are all exercised (per CLAUDE.md
 * "Project Conventions": acceptance tests use real HTTP for HTTP-only fakes).
 */
class InsightsRoutesAcceptanceTest : FunSpec({

    test("POST /api/v1/insights/explain/var proxies request and response bodies verbatim to ai-insights-service") {
        val receivedBody = AtomicReference<String?>(null)
        val cannedUpstreamResponse = """
            {
              "narrative": "Portfolio VaR is driven primarily by long tech equity exposure.",
              "bullets": [
                "AAPL contributes 35.2% of total VaR",
                "MSFT contributes 24.1% of total VaR",
                "Regime: high_vol"
              ],
              "model": "claude-canned-stub",
              "mode": "canned"
            }
        """.trimIndent()

        val backend = BackendStubServer {
            post("/api/v1/insights/explain/var") {
                receivedBody.set(call.receiveText())
                call.respondText(cannedUpstreamResponse, ContentType.Application.Json)
            }
        }

        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        val requestBody = """
            {
              "method": "historical",
              "confidence": 0.99,
              "horizon_days": 1,
              "value_usd": 1234567.89,
              "top_contributors": [
                {"instrument": "AAPL", "contribution_pct": 35.2},
                {"instrument": "MSFT", "contribution_pct": 24.1}
              ],
              "regime": "high_vol"
            }
        """.trimIndent()

        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl) }

                val response = client.post("/api/v1/insights/explain/var") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                // Status pass-through
                response.status shouldBe HttpStatusCode.OK

                // Request body reached ai-insights-service verbatim
                val upstreamReceived = receivedBody.get()
                checkNotNull(upstreamReceived) { "ai-insights-service stub did not receive a request" }
                Json.parseToJsonElement(upstreamReceived) shouldBe Json.parseToJsonElement(requestBody)

                // Response body returned to caller verbatim
                Json.parseToJsonElement(response.bodyAsText()) shouldBe
                    Json.parseToJsonElement(cannedUpstreamResponse)
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/insights/explain/var propagates upstream non-2xx status to the caller") {
        val backend = BackendStubServer {
            post("/api/v1/insights/explain/var") {
                call.respondText(
                    """{"error":"invalid method"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl) }
                val response = client.post("/api/v1/insights/explain/var") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"method":"bogus"}""")
                }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldBe """{"error":"invalid method"}"""
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/insights/explain/report proxies request and response bodies verbatim to ai-insights-service") {
        val receivedBody = AtomicReference<String?>(null)
        val cannedUpstreamResponse = """
            {
              "title": "Daily Risk Report — 2026-05-18",
              "sections": [
                {
                  "heading": "Executive Summary",
                  "body": "Portfolio VaR rose 12% driven by tech equity beta to a high-vol regime."
                },
                {
                  "heading": "Top Risk Contributors",
                  "body": "AAPL (35.2%), MSFT (24.1%), NVDA (15.8%)."
                }
              ],
              "model": "claude-canned-stub",
              "mode": "canned"
            }
        """.trimIndent()

        val backend = BackendStubServer {
            post("/api/v1/insights/explain/report") {
                receivedBody.set(call.receiveText())
                call.respondText(cannedUpstreamResponse, ContentType.Application.Json)
            }
        }

        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        val requestBody = """
            {
              "report_date": "2026-05-18",
              "portfolio_id": "PF-001",
              "var_value_usd": 1234567.89,
              "var_change_pct": 12.0,
              "top_contributors": [
                {"instrument": "AAPL", "contribution_pct": 35.2},
                {"instrument": "MSFT", "contribution_pct": 24.1},
                {"instrument": "NVDA", "contribution_pct": 15.8}
              ],
              "regime": "high_vol"
            }
        """.trimIndent()

        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl) }

                val response = client.post("/api/v1/insights/explain/report") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                // Status pass-through
                response.status shouldBe HttpStatusCode.OK

                // Request body reached ai-insights-service verbatim
                val upstreamReceived = receivedBody.get()
                checkNotNull(upstreamReceived) { "ai-insights-service stub did not receive a request" }
                Json.parseToJsonElement(upstreamReceived) shouldBe Json.parseToJsonElement(requestBody)

                // Response body returned to caller verbatim
                Json.parseToJsonElement(response.bodyAsText()) shouldBe
                    Json.parseToJsonElement(cannedUpstreamResponse)
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/insights/explain/report propagates upstream non-2xx status to the caller") {
        val backend = BackendStubServer {
            post("/api/v1/insights/explain/report") {
                call.respondText(
                    """{"error":"missing report_date"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl) }
                val response = client.post("/api/v1/insights/explain/report") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"portfolio_id":"PF-001"}""")
                }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldBe """{"error":"missing report_date"}"""
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
