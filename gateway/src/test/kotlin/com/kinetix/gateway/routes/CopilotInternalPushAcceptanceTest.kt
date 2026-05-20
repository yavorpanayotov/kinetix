package com.kinetix.gateway.routes

import com.kinetix.gateway.websocket.CopilotBroadcaster
import com.kinetix.gateway.websocket.CopilotWebSocketMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Acceptance test for the gateway's cluster-internal intraday-push route
 * `POST /internal/copilot/push` (PR 7 / ADR-0036).
 *
 * The route accepts the [com.kinetix.gateway.dtos.CopilotPushRequest] payload
 * that `ai-insights-service` composes when an intraday risk threshold breaches
 * and enqueues it on the [CopilotBroadcaster] for `/ws/copilot` fan-out. It is
 * **internal-only**: no JWT challenge, but a shared-secret header
 * ([INTERNAL_REQUEST_TOKEN_HEADER]) gates it so external callers — who cannot
 * present the in-cluster token — are rejected.
 *
 * The test asserts both halves of that contract:
 *  - an external request (no token / wrong token) is rejected with `403` and
 *    nothing reaches the broadcaster;
 *  - an internal request (correct token) flows through with `202` and the
 *    payload lands on the broadcaster verbatim.
 *
 * Per CLAUDE.md, gateway route acceptance tests use the Ktor test host
 * (`testApplication`); the broadcaster's WebSocket fan-out is checkbox 7.6's
 * concern, so a recording subclass stands in to observe enqueued messages.
 */
class CopilotInternalPushAcceptanceTest : FunSpec({

    val internalToken = "cluster-internal-token"

    /** Records every message enqueued for fan-out so the test can inspect it. */
    class RecordingCopilotBroadcaster : CopilotBroadcaster() {
        val enqueued = CopyOnWriteArrayList<CopilotWebSocketMessage>()
        override suspend fun broadcast(message: CopilotWebSocketMessage) {
            enqueued.add(message)
            super.broadcast(message)
        }
    }

    fun Application.configureCopilotInternalRoutes(broadcaster: CopilotBroadcaster) {
        install(ContentNegotiation) { json() }
        routing {
            copilotInternalRoutes(broadcaster, CopilotInternalAuth(internalToken))
        }
    }

    val samplePayload = """
        {
          "alert_type": "VAR_BREACH",
          "severity": "critical",
          "book_id": "fx-main",
          "headline": "Critical VAR_BREACH on fx-main: VaR 7,500,000 exceeds limit 5,000,000",
          "context_bullets": [
            "Current VaR: 7,500,000 USD — up sharply into the ECB window",
            "Threshold: 5,000,000 USD book VaR ceiling"
          ],
          "sources": [
            {
              "tool": "risk.results",
              "params": {"book_id": "fx-main", "alert_type": "VAR_BREACH"},
              "result_field": "varValue",
              "result_value": 7500000.0,
              "result_currency": "USD",
              "as_of_timestamp": "2026-05-20T09:00:00Z",
              "data_source": "risk-orchestrator",
              "freshness_seconds": 0,
              "quality_flags": []
            }
          ],
          "session_id": "9f2b1c4d-0000-0000-0000-000000000001",
          "generated_at": "2026-05-20T09:00:05Z"
        }
    """.trimIndent()

    test("external request without the internal token is rejected with 403 and never reaches the broadcaster") {
        val broadcaster = RecordingCopilotBroadcaster()
        testApplication {
            application { configureCopilotInternalRoutes(broadcaster) }

            val response = client.post("/internal/copilot/push") {
                contentType(ContentType.Application.Json)
                setBody(samplePayload)
            }

            response.status shouldBe HttpStatusCode.Forbidden
            broadcaster.enqueued shouldHaveSize 0
        }
    }

    test("external request with a wrong internal token is rejected with 403 and never reaches the broadcaster") {
        val broadcaster = RecordingCopilotBroadcaster()
        testApplication {
            application { configureCopilotInternalRoutes(broadcaster) }

            val response = client.post("/internal/copilot/push") {
                header(INTERNAL_REQUEST_TOKEN_HEADER, "not-the-token")
                contentType(ContentType.Application.Json)
                setBody(samplePayload)
            }

            response.status shouldBe HttpStatusCode.Forbidden
            broadcaster.enqueued shouldHaveSize 0
        }
    }

    test("internal request with the correct token is accepted with 202 and the payload reaches the broadcaster") {
        val broadcaster = RecordingCopilotBroadcaster()
        testApplication {
            application { configureCopilotInternalRoutes(broadcaster) }

            val response = client.post("/internal/copilot/push") {
                header(INTERNAL_REQUEST_TOKEN_HEADER, internalToken)
                contentType(ContentType.Application.Json)
                setBody(samplePayload)
            }

            response.status shouldBe HttpStatusCode.Accepted

            broadcaster.enqueued shouldHaveSize 1
            val message = broadcaster.enqueued.single()
            message.type shouldBe "intraday_push"

            val push = message.push
            push.alertType shouldBe "VAR_BREACH"
            push.severity shouldBe "critical"
            push.bookId shouldBe "fx-main"
            push.headline shouldBe
                "Critical VAR_BREACH on fx-main: VaR 7,500,000 exceeds limit 5,000,000"
            push.contextBullets shouldHaveSize 2
            push.sessionId shouldBe "9f2b1c4d-0000-0000-0000-000000000001"
            push.generatedAt shouldBe "2026-05-20T09:00:05Z"

            // The provenance trail is forwarded untransformed — the gateway
            // does not own the citation schema.
            push.sources shouldHaveSize 1
            val source = push.sources.single()
            Json.parseToJsonElement(source.toString()).shouldNotBeNull()
        }
    }
})
