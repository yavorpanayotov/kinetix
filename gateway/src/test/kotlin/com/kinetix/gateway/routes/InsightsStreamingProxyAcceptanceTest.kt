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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Acceptance test for the new `streamProxyToInsights` helper. Mirrors the
 * structure of [InsightsRoutesAcceptanceTest] but registers a streaming-only
 * test route inside the `testApplication` block so the helper is exercised
 * end-to-end without altering production route registration. The backend stub
 * emits `text/event-stream` responses to mimic the real
 * `ai-insights-service`'s `POST /api/v1/insights/chat` contract.
 */
class InsightsStreamingProxyAcceptanceTest : FunSpec({

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

    fun newHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        install(HttpTimeout) {
            // Streaming routes need a long socket-read budget; the helper itself
            // imposes no timeout, so the client must allow long-lived streams.
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }

    test("streamProxyToInsights forwards SSE bytes verbatim from backend to client") {
        val frames = listOf(
            "data: {\"delta\":\"hello\"}\n\n",
            "data: {\"delta\":\"world\"}\n\n",
            "data: {\"done\":true}\n\n",
        )
        val backend = BackendStubServer {
            post("/test/sse") {
                call.respondSseFrames(frames)
            }
        }
        val httpClient = newHttpClient()
        try {
            testApplication {
                application {
                    moduleWithInsightsProxy(httpClient, backend.baseUrl)
                    routing {
                        post("/test/streaming-proxy") {
                            streamProxyToInsights(httpClient, "${backend.baseUrl}/test/sse", call)
                        }
                    }
                }

                val response = client.post("/test/streaming-proxy") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"hello"}""")
                }

                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "data: {\"delta\":\"hello\"}"
                body shouldContain "data: {\"delta\":\"world\"}"
                body shouldContain "data: {\"done\":true}"

                // Frames arrive in order
                val helloAt = body.indexOf("\"delta\":\"hello\"")
                val worldAt = body.indexOf("\"delta\":\"world\"")
                val doneAt = body.indexOf("\"done\":true")
                (helloAt < worldAt) shouldBe true
                (worldAt < doneAt) shouldBe true
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("streamProxyToInsights preserves text/event-stream content-type") {
        val backend = BackendStubServer {
            post("/test/sse") {
                call.respondSseFrames(listOf("data: {\"delta\":\"x\"}\n\n", "data: {\"done\":true}\n\n"))
            }
        }
        val httpClient = newHttpClient()
        try {
            testApplication {
                application {
                    moduleWithInsightsProxy(httpClient, backend.baseUrl)
                    routing {
                        post("/test/streaming-proxy") {
                            streamProxyToInsights(httpClient, "${backend.baseUrl}/test/sse", call)
                        }
                    }
                }

                val response = client.post("/test/streaming-proxy") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"ping"}""")
                }

                response.status shouldBe HttpStatusCode.OK
                val responseCt = response.contentType()?.toString() ?: ""
                responseCt shouldStartWith "text/event-stream"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("streamProxyToInsights forwards headers to upstream") {
        val capturedUserId = AtomicReference<String?>(null)
        val backend = BackendStubServer {
            post("/test/sse") {
                capturedUserId.set(call.request.headers["X-User-Id"])
                call.respondSseFrames(listOf("data: {\"done\":true}\n\n"))
            }
        }
        val httpClient = newHttpClient()
        try {
            testApplication {
                application {
                    moduleWithInsightsProxy(httpClient, backend.baseUrl)
                    routing {
                        post("/test/streaming-proxy") {
                            streamProxyToInsights(httpClient, "${backend.baseUrl}/test/sse", call)
                        }
                    }
                }

                val response = client.post("/test/streaming-proxy") {
                    contentType(ContentType.Application.Json)
                    header("X-User-Id", "trader-42")
                    setBody("""{"message":"ping"}""")
                }

                response.status shouldBe HttpStatusCode.OK
                // Consume the body so the underlying connection completes before assertions.
                response.bodyAsText()
                capturedUserId.get() shouldBe "trader-42"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("streamProxyToInsights forwards request body to upstream") {
        val receivedBody = AtomicReference<String?>(null)
        val backend = BackendStubServer {
            post("/test/sse") {
                receivedBody.set(call.receiveText())
                call.respondSseFrames(listOf("data: {\"done\":true}\n\n"))
            }
        }
        val httpClient = newHttpClient()
        val requestBody = """{"message":"explain VaR","page_context":{"page":"var"}}"""
        try {
            testApplication {
                application {
                    moduleWithInsightsProxy(httpClient, backend.baseUrl)
                    routing {
                        post("/test/streaming-proxy") {
                            streamProxyToInsights(httpClient, "${backend.baseUrl}/test/sse", call)
                        }
                    }
                }

                val response = client.post("/test/streaming-proxy") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText()
                receivedBody.get() shouldBe requestBody
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("streamProxyToInsights propagates non-200 status from upstream") {
        val backend = BackendStubServer {
            post("/test/sse") {
                call.respondText(
                    """{"error":"upstream boom"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadGateway,
                )
            }
        }
        val httpClient = newHttpClient()
        try {
            testApplication {
                application {
                    moduleWithInsightsProxy(httpClient, backend.baseUrl)
                    routing {
                        post("/test/streaming-proxy") {
                            streamProxyToInsights(httpClient, "${backend.baseUrl}/test/sse", call)
                        }
                    }
                }

                val response = client.post("/test/streaming-proxy") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"ping"}""")
                }

                response.status shouldBe HttpStatusCode.BadGateway
                response.bodyAsText() shouldContain "upstream boom"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("streamProxyToInsights does not buffer the entire response") {
        // The backend writes a first frame, pauses 100 ms, then writes more
        // frames. If the proxy buffers, the client wouldn't see anything until
        // the backend finished. With true streaming, the first frame is
        // observed well before the upstream connection closes.
        //
        // We run BOTH the backend stub and the gateway in real embedded Netty
        // servers on random ports so the HTTP wire actually carries chunks in
        // real time — the in-memory testApplication client coalesces the body
        // and would defeat the timing assertion.
        val backend = BackendStubServer {
            post("/test/sse") {
                call.respondSseFrames(
                    listOf(
                        "data: {\"delta\":\"first\"}\n\n",
                        "data: {\"delta\":\"second\"}\n\n",
                        "data: {\"done\":true}\n\n",
                    ),
                    interFrameDelayMillis = 100,
                )
            }
        }
        val upstreamClient = newHttpClient()
        val gateway = io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, port = 0) {
            routing {
                post("/test/streaming-proxy") {
                    streamProxyToInsights(upstreamClient, "${backend.baseUrl}/test/sse", call)
                }
            }
        }.start(wait = false)
        val gatewayPort = runBlocking { gateway.engine.resolvedConnectors().first().port }
        val downstreamClient = newHttpClient()
        try {
            val firstByteAt = AtomicLong(-1)
            val closeAt = AtomicLong(-1)
            val firstFrameContent = AtomicReference<String?>(null)
            val startedAt = System.currentTimeMillis()

            runBlocking {
                downstreamClient.prepareRequest("http://localhost:$gatewayPort/test/streaming-proxy") {
                    method = HttpMethod.Post
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"message":"stream"}""")
                }.execute { resp ->
                    val ch = resp.bodyAsChannel()
                    while (true) {
                        val line = ch.readUTF8Line() ?: break
                        if (firstByteAt.get() < 0 && line.isNotEmpty()) {
                            firstByteAt.set(System.currentTimeMillis() - startedAt)
                            firstFrameContent.set(line)
                        }
                    }
                    closeAt.set(System.currentTimeMillis() - startedAt)
                }
            }

            // Streaming check: the first frame arrives well before the
            // connection closes. The backend's two 100 ms inter-frame delays
            // guarantee ~200 ms backend time, so a buffered implementation
            // would produce firstByteAt ≈ closeAt. We assert a 50 ms cushion.
            val first = firstByteAt.get()
            val close = closeAt.get()
            (first >= 0) shouldBe true
            (close >= 0) shouldBe true
            (first < close - 50) shouldBe true
            firstFrameContent.get()!! shouldContain "first"
        } finally {
            gateway.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
            downstreamClient.close()
            upstreamClient.close()
            backend.close()
        }
    }
})
