package com.kinetix.gateway.routes

import com.kinetix.gateway.moduleWithAuditProxy
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json

/**
 * Acceptance test for the gateway audit proxy ([com.kinetix.gateway.routes.auditProxyRoutes]).
 * The proxy forwards `GET /api/v1/audit/events` to the audit-service. This test stands up a
 * fake audit-service via [BackendStubServer] and asserts the proxy passes the support query
 * parameters — `bookId`, `tradeId`, `eventType`, `from`, `to`, `afterId`, `limit` — through
 * to the upstream unchanged.
 */
class AuditProxyRoutesAcceptanceTest : FunSpec({

    test("GET audit/events forwards bookId, tradeId, eventType, from, to and pagination params unchanged") {
        val backend = BackendStubServer {
            get("/api/v1/audit/events") {
                call.respondText(contentType = ContentType.Application.Json, text = "[]")
            }
        }

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        try {
            testApplication {
                application { moduleWithAuditProxy(httpClient, backend.baseUrl) }

                val response = client.get(
                    "/api/v1/audit/events" +
                        "?bookId=BOOK-1" +
                        "&tradeId=TRD-42" +
                        "&eventType=TRADE_BOOKED" +
                        "&from=2026-05-01T00:00:00Z" +
                        "&to=2026-05-20T23:59:59Z" +
                        "&afterId=100" +
                        "&limit=25",
                )
                response.status shouldBe HttpStatusCode.OK

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/audit/events" }
                recorded.method shouldBe "GET"
                recorded.query["bookId"] shouldBe listOf("BOOK-1")
                recorded.query["tradeId"] shouldBe listOf("TRD-42")
                recorded.query["eventType"] shouldBe listOf("TRADE_BOOKED")
                recorded.query["from"] shouldBe listOf("2026-05-01T00:00:00Z")
                recorded.query["to"] shouldBe listOf("2026-05-20T23:59:59Z")
                recorded.query["afterId"] shouldBe listOf("100")
                recorded.query["limit"] shouldBe listOf("25")
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET audit/events omits absent query parameters from the upstream request") {
        val backend = BackendStubServer {
            get("/api/v1/audit/events") {
                call.respondText(contentType = ContentType.Application.Json, text = "[]")
            }
        }

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        try {
            testApplication {
                application { moduleWithAuditProxy(httpClient, backend.baseUrl) }

                val response = client.get("/api/v1/audit/events?tradeId=TRD-99")
                response.status shouldBe HttpStatusCode.OK

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/audit/events" }
                recorded.query["tradeId"] shouldBe listOf("TRD-99")
                recorded.query.containsKey("bookId") shouldBe false
                recorded.query.containsKey("eventType") shouldBe false
                recorded.query.containsKey("from") shouldBe false
                recorded.query.containsKey("to") shouldBe false
                recorded.query.containsKey("afterId") shouldBe false
                recorded.query.containsKey("limit") shouldBe false
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
