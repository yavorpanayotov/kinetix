package com.kinetix.gateway.routes

import com.kinetix.gateway.moduleWithAuditProxy
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acceptance test for the gateway Activity feed ([com.kinetix.gateway.routes.auditProxyRoutes]).
 *
 * Plan P2 #28: the Activity tab is fed by the gateway audit-events projection. The concern was
 * that only `TRADE_BOOKED` events surfaced, with governance / risk / reconciliation lifecycle
 * events (`LIMIT_BREACH`, `RUN_PROMOTED`, `RECONCILIATION_BREAK`, `TRADE_AMENDED`,
 * `TRADE_CANCELLED`) silently dropped.
 *
 * This stands up a fake audit-service via [BackendStubServer] returning a heterogeneous batch of
 * event types and asserts every type reaches the gateway projection with its `eventType` field
 * preserved on the wire — so the Activity tab can render the full lifecycle, not just bookings.
 */
class ActivityFeedAllEventTypesAcceptanceTest : FunSpec({

    test("GET audit/events surfaces non-TRADE_BOOKED event types (LIMIT_BREACH, RUN_PROMOTED, RECONCILIATION_BREAK) to the gateway projection") {
        val upstreamBody = """
            [
              {"id":1,"eventType":"TRADE_BOOKED","tradeId":"TRD-1","bookId":"BOOK-1","receivedAt":"2026-05-29T09:00:00Z"},
              {"id":2,"eventType":"LIMIT_BREACH","limitId":"LIM-7","bookId":"BOOK-1","receivedAt":"2026-05-29T09:01:00Z"},
              {"id":3,"eventType":"RUN_PROMOTED","modelName":"VAR-EOD","receivedAt":"2026-05-29T09:02:00Z"},
              {"id":4,"eventType":"RECONCILIATION_BREAK","bookId":"BOOK-2","receivedAt":"2026-05-29T09:03:00Z"},
              {"id":5,"eventType":"TRADE_AMENDED","tradeId":"TRD-2","bookId":"BOOK-1","receivedAt":"2026-05-29T09:04:00Z"},
              {"id":6,"eventType":"TRADE_CANCELLED","tradeId":"TRD-3","bookId":"BOOK-1","receivedAt":"2026-05-29T09:05:00Z"}
            ]
        """.trimIndent()

        val backend = BackendStubServer {
            get("/api/v1/audit/events") {
                call.respondText(contentType = ContentType.Application.Json, text = upstreamBody)
            }
        }

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        try {
            testApplication {
                application { moduleWithAuditProxy(httpClient, backend.baseUrl) }

                val response = client.get("/api/v1/audit/events?limit=100")
                response.status shouldBe HttpStatusCode.OK

                val events = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                val eventTypes = events.map { it.jsonObject["eventType"]!!.jsonPrimitive.content }

                eventTypes shouldContainExactlyInAnyOrder listOf(
                    "TRADE_BOOKED",
                    "LIMIT_BREACH",
                    "RUN_PROMOTED",
                    "RECONCILIATION_BREAK",
                    "TRADE_AMENDED",
                    "TRADE_CANCELLED",
                )
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET audit/events forwards an eventType filter for a non-TRADE_BOOKED type to the upstream unchanged") {
        val backend = BackendStubServer {
            get("/api/v1/audit/events") {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    text = """[{"id":2,"eventType":"LIMIT_BREACH","limitId":"LIM-7","receivedAt":"2026-05-29T09:01:00Z"}]""",
                )
            }
        }

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        try {
            testApplication {
                application { moduleWithAuditProxy(httpClient, backend.baseUrl) }

                val response = client.get("/api/v1/audit/events?eventType=LIMIT_BREACH")
                response.status shouldBe HttpStatusCode.OK

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/audit/events" }
                recorded.query["eventType"] shouldBe listOf("LIMIT_BREACH")

                val events = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                events.single().jsonObject["eventType"]!!.jsonPrimitive.content shouldBe "LIMIT_BREACH"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
