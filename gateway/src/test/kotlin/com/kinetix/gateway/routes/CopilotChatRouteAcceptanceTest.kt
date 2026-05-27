package com.kinetix.gateway.routes

import com.kinetix.gateway.moduleWithInsightsProxy
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/**
 * Acceptance test for the gateway's `POST /api/v1/insights/chat` route — the
 * Copilot chat surface registered on top of [streamProxyToInsights]. The
 * upstream `ai-insights-service` emits a long-lived `text/event-stream`
 * response; the gateway must:
 *   1. Forward the request body verbatim (the chat schema is owned by the
 *      ai-insights-service, not the gateway).
 *   2. Preserve the `text/event-stream` content-type so the browser parses
 *      frames incrementally.
 *   3. Stream each SSE frame through to the client as it is produced by the
 *      upstream — no buffering of the entire response.
 *   4. Forward identifying headers (e.g. `X-User-Id`) so the upstream can
 *      attribute the chat session.
 *   5. Survive an upstream that pauses between chunks for longer than the
 *      shared 5 s `requestTimeoutMillis` configured for non-streaming
 *      routes — proving the dedicated streaming HttpClient (with
 *      `requestTimeoutMillis = Long.MAX_VALUE`) is load-bearing.
 *   6. Propagate non-200 status codes from the upstream.
 */
class CopilotChatRouteAcceptanceTest : FunSpec({

    suspend fun ApplicationCall.respondSseFrames(
        frames: List<String>,
        interFrameDelayMillis: Long = 0,
    ) {
        respondBytesWriter(contentType = ContentType.Text.EventStream) {
            for ((index, frame) in frames.withIndex()) {
                if (index > 0 && interFrameDelayMillis > 0) {
                    delay(interFrameDelayMillis)
                }
                writeFully(frame.toByteArray(Charsets.UTF_8))
                flush()
            }
        }
    }

    fun streamingHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        install(HttpTimeout) {
            // Dedicated streaming budget — matches the production
            // streamingHttpClient in Application.devModule. socketTimeoutMillis
            // is also disabled so a long upstream "thinking" gap before the
            // first SSE frame does not kill the connection.
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 2_000
        }
    }

    fun bufferedHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        install(HttpTimeout) {
            // Mirrors the production buffered httpClient timeouts.
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 2_000
        }
    }

    test("POST /api/v1/insights/chat proxies request body verbatim to ai-insights-service") {
        val receivedBody = AtomicReference<String?>(null)
        val backend = BackendStubServer {
            post("/api/v1/insights/chat") {
                receivedBody.set(call.receiveText())
                call.respondSseFrames(
                    listOf(
                        "data: {\"delta\":\"echo\"}\n\n",
                        "data: {\"done\":true}\n\n",
                    ),
                )
            }
        }
        val httpClient = bufferedHttpClient()
        val streaming = streamingHttpClient()
        val requestBody =
            """{"message":"Why did VaR spike?","page_context":{"page":"var","portfolio_id":"PF-001"}}"""
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl, streaming) }

                val response = client.post("/api/v1/insights/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText()
                receivedBody.get() shouldBe requestBody
            }
        } finally {
            httpClient.close()
            streaming.close()
            backend.close()
        }
    }

    test("POST /api/v1/insights/chat preserves text/event-stream content-type") {
        val backend = BackendStubServer {
            post("/api/v1/insights/chat") {
                call.respondSseFrames(
                    listOf(
                        "data: {\"delta\":\"x\"}\n\n",
                        "data: {\"done\":true}\n\n",
                    ),
                )
            }
        }
        val httpClient = bufferedHttpClient()
        val streaming = streamingHttpClient()
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl, streaming) }

                val response = client.post("/api/v1/insights/chat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"ping"}""")
                }

                response.status shouldBe HttpStatusCode.OK
                val responseCt = response.contentType()?.toString() ?: ""
                responseCt shouldStartWith "text/event-stream"
            }
        } finally {
            httpClient.close()
            streaming.close()
            backend.close()
        }
    }

    test("POST /api/v1/insights/chat streams at least one SSE chunk through to the client") {
        val backend = BackendStubServer {
            post("/api/v1/insights/chat") {
                call.respondSseFrames(
                    listOf(
                        "data: {\"delta\":\"hello\"}\n\n",
                        "data: {\"done\":true,\"reason\":\"end_turn\"}\n\n",
                    ),
                )
            }
        }
        val httpClient = bufferedHttpClient()
        val streaming = streamingHttpClient()
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl, streaming) }

                val response = client.post("/api/v1/insights/chat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"hi"}""")
                }

                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "data: {\"delta\":\"hello\"}"
                body shouldContain "\"done\":true"

                // Frames arrive in order.
                val deltaAt = body.indexOf("\"delta\":\"hello\"")
                val doneAt = body.indexOf("\"done\":true")
                (deltaAt < doneAt) shouldBe true
            }
        } finally {
            httpClient.close()
            streaming.close()
            backend.close()
        }
    }

    test("POST /api/v1/insights/chat forwards X-User-Id header to upstream") {
        val capturedUserId = AtomicReference<String?>(null)
        val backend = BackendStubServer {
            post("/api/v1/insights/chat") {
                capturedUserId.set(call.request.headers["X-User-Id"])
                call.respondSseFrames(listOf("data: {\"done\":true}\n\n"))
            }
        }
        val httpClient = bufferedHttpClient()
        val streaming = streamingHttpClient()
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl, streaming) }

                val response = client.post("/api/v1/insights/chat") {
                    contentType(ContentType.Application.Json)
                    header("X-User-Id", "trader-42")
                    setBody("""{"message":"ping"}""")
                }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText()
                capturedUserId.get() shouldBe "trader-42"
            }
        } finally {
            httpClient.close()
            streaming.close()
            backend.close()
        }
    }

    test("POST /api/v1/insights/chat survives upstream that pauses between chunks for longer than the default 5s timeout") {
        // The backend emits a first frame, sleeps ~1500 ms, then emits a
        // second frame. The shared (buffered) HTTP client is configured
        // with `requestTimeoutMillis = 5_000`, but the chat route uses the
        // dedicated streamingHttpClient with `requestTimeoutMillis = Long.MAX_VALUE`,
        // so the second frame must still arrive. We run BOTH the backend
        // stub and the gateway in real embedded Netty servers on random
        // ports so HTTP wire chunking is genuinely exercised — the
        // in-memory testApplication client coalesces the body and would
        // not let us observe per-frame timing.
        val backend = BackendStubServer {
            post("/api/v1/insights/chat") {
                call.respondSseFrames(
                    listOf(
                        "data: {\"delta\":\"first\"}\n\n",
                        "data: {\"delta\":\"second\"}\n\n",
                        "data: {\"done\":true}\n\n",
                    ),
                    interFrameDelayMillis = 1_500,
                )
            }
        }
        // The buffered httpClient passed to insightsRoutes will be used for
        // /api/v1/insights/explain/* — never for /chat. We still construct
        // it to mirror production wiring.
        val bufferedClient = bufferedHttpClient()
        val streaming = streamingHttpClient()
        val downstream = streamingHttpClient()
        val gateway = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/v1/insights/chat") {
                    streamProxyToInsights(streaming, "${backend.baseUrl}/api/v1/insights/chat", call)
                }
            }
        }.start(wait = false)
        val gatewayPort = runBlocking { gateway.engine.resolvedConnectors().first().port }
        try {
            val sawFirst = AtomicReference(false)
            val sawSecond = AtomicReference(false)
            val sawDone = AtomicReference(false)

            runBlocking {
                downstream.prepareRequest("http://localhost:$gatewayPort/api/v1/insights/chat") {
                    method = HttpMethod.Post
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"message":"stream"}""")
                }.execute { resp ->
                    val ch = resp.bodyAsChannel()
                    while (true) {
                        val line = ch.readUTF8Line() ?: break
                        if (line.contains("\"delta\":\"first\"")) sawFirst.set(true)
                        if (line.contains("\"delta\":\"second\"")) sawSecond.set(true)
                        if (line.contains("\"done\":true")) sawDone.set(true)
                    }
                }
            }

            sawFirst.get() shouldBe true
            sawSecond.get() shouldBe true
            sawDone.get() shouldBe true
        } finally {
            gateway.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
            downstream.close()
            streaming.close()
            bufferedClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/insights/chat propagates non-200 status from upstream") {
        val backend = BackendStubServer {
            post("/api/v1/insights/chat") {
                call.respondText(
                    """{"error":"upstream unavailable"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
        }
        val httpClient = bufferedHttpClient()
        val streaming = streamingHttpClient()
        try {
            testApplication {
                application { moduleWithInsightsProxy(httpClient, backend.baseUrl, streaming) }

                val response = client.post("/api/v1/insights/chat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"hi"}""")
                }

                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.bodyAsText() shouldContain "upstream unavailable"
            }
        } finally {
            httpClient.close()
            streaming.close()
            backend.close()
        }
    }
})
