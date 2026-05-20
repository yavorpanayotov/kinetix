package com.kinetix.gateway.routes

import com.kinetix.gateway.moduleWithInsightsProxy
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.writeFully
import java.util.concurrent.atomic.AtomicInteger

/**
 * Acceptance test for AI-v2 PR 10.2 — per-user rate limiting on the gateway's
 * Copilot routes.
 *
 * The two streaming Copilot routes — `POST /api/v1/insights/chat` and
 * `POST /api/v1/insights/queries/{id}/run` — are rate-limited at the gateway to
 * **10 requests per user per minute**. The limiter is keyed by the user's JWT
 * `sub` claim (surfaced as `X-User-Id` once the JWT→header bridge has run), so
 * two different users never share a bucket. The 11th request from one user
 * inside a minute is rejected with HTTP 429 without ever reaching the upstream
 * `ai-insights-service`; a different user's bucket is unaffected.
 *
 * The upstream is a real embedded Netty server (per CLAUDE.md "Project
 * Conventions": acceptance tests use real HTTP for HTTP-only collaborators). An
 * upstream call counter proves rejected requests are not proxied.
 */
class CopilotRateLimitAcceptanceTest : FunSpec({

    suspend fun ApplicationCall.respondSseFrame() {
        respondBytesWriter(contentType = ContentType.Text.EventStream) {
            writeFully("data: {\"done\":true}\n\n".toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    fun newHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    test("POST /api/v1/insights/chat allows the first 10 requests from a user within a minute") {
        val backend = BackendStubServer {
            post("/api/v1/insights/chat") { call.respondSseFrame() }
        }
        val httpClient = newHttpClient()
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl, httpClient) }

                repeat(10) {
                    val response = client.post("/api/v1/insights/chat") {
                        contentType(ContentType.Application.Json)
                        header("X-User-Id", "trader-1")
                        setBody("""{"message":"ping"}""")
                    }
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/insights/chat returns 429 on the 11th request from the same user within a minute") {
        val upstreamCalls = AtomicInteger(0)
        val backend = BackendStubServer {
            post("/api/v1/insights/chat") {
                upstreamCalls.incrementAndGet()
                call.respondSseFrame()
            }
        }
        val httpClient = newHttpClient()
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl, httpClient) }

                repeat(10) {
                    val ok = client.post("/api/v1/insights/chat") {
                        contentType(ContentType.Application.Json)
                        header("X-User-Id", "trader-2")
                        setBody("""{"message":"ping"}""")
                    }
                    ok.status shouldBe HttpStatusCode.OK
                }

                val rejected = client.post("/api/v1/insights/chat") {
                    contentType(ContentType.Application.Json)
                    header("X-User-Id", "trader-2")
                    setBody("""{"message":"ping"}""")
                }

                rejected.status shouldBe HttpStatusCode.TooManyRequests
                // The rejected request must never reach the upstream service.
                upstreamCalls.get() shouldBe 10
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/insights/queries/{id}/run returns 429 on the 11th request from the same user within a minute") {
        val upstreamCalls = AtomicInteger(0)
        val backend = BackendStubServer {
            post("/api/v1/insights/queries/{id}/run") {
                upstreamCalls.incrementAndGet()
                call.respondSseFrame()
            }
        }
        val httpClient = newHttpClient()
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl, httpClient) }

                repeat(10) {
                    val ok = client.post("/api/v1/insights/queries/limit-breaches/run") {
                        contentType(ContentType.Application.Json)
                        header("X-User-Id", "trader-3")
                        setBody("""{"params":{"book_id":"port-1"}}""")
                    }
                    ok.status shouldBe HttpStatusCode.OK
                }

                val rejected = client.post("/api/v1/insights/queries/limit-breaches/run") {
                    contentType(ContentType.Application.Json)
                    header("X-User-Id", "trader-3")
                    setBody("""{"params":{"book_id":"port-1"}}""")
                }

                rejected.status shouldBe HttpStatusCode.TooManyRequests
                upstreamCalls.get() shouldBe 10
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("each user has an independent rate-limit bucket") {
        val backend = BackendStubServer {
            post("/api/v1/insights/chat") { call.respondSseFrame() }
        }
        val httpClient = newHttpClient()
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl, httpClient) }

                // Exhaust user A's bucket.
                repeat(10) {
                    client.post("/api/v1/insights/chat") {
                        contentType(ContentType.Application.Json)
                        header("X-User-Id", "user-a")
                        setBody("""{"message":"ping"}""")
                    }
                }
                val aRejected = client.post("/api/v1/insights/chat") {
                    contentType(ContentType.Application.Json)
                    header("X-User-Id", "user-a")
                    setBody("""{"message":"ping"}""")
                }
                aRejected.status shouldBe HttpStatusCode.TooManyRequests

                // User B still has a full bucket — not affected by user A.
                val bAllowed = client.post("/api/v1/insights/chat") {
                    contentType(ContentType.Application.Json)
                    header("X-User-Id", "user-b")
                    setBody("""{"message":"ping"}""")
                }
                bAllowed.status shouldBe HttpStatusCode.OK
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("a 429 response carries a Retry-After header and a rate_limited error code") {
        val backend = BackendStubServer {
            post("/api/v1/insights/chat") { call.respondSseFrame() }
        }
        val httpClient = newHttpClient()
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl, httpClient) }

                repeat(10) {
                    client.post("/api/v1/insights/chat") {
                        contentType(ContentType.Application.Json)
                        header("X-User-Id", "trader-4")
                        setBody("""{"message":"ping"}""")
                    }
                }
                val rejected = client.post("/api/v1/insights/chat") {
                    contentType(ContentType.Application.Json)
                    header("X-User-Id", "trader-4")
                    setBody("""{"message":"ping"}""")
                }

                rejected.status shouldBe HttpStatusCode.TooManyRequests
                (rejected.headers["Retry-After"] != null) shouldBe true
                rejected.bodyAsText().contains("rate_limited") shouldBe true
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
