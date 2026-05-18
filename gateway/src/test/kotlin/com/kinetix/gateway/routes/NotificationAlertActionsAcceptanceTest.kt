package com.kinetix.gateway.routes

import com.kinetix.gateway.client.HttpNotificationServiceClient
import com.kinetix.gateway.module
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
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acceptance tests for the gateway proxy routes that forward alert
 * lifecycle actions (escalate, resolve) to notification-service. A real
 * embedded Netty backend records what the gateway forwarded so we can
 * assert that bodies and status codes are passed through faithfully.
 */
class NotificationAlertActionsAcceptanceTest : FunSpec({

    fun alertJson(id: String, status: String, escalatedTo: String? = null, resolvedReason: String? = null): String =
        buildString {
            append("""{"id":"$id","ruleId":"r1","ruleName":"VaR","type":"VAR_BREACH","severity":"CRITICAL",""")
            append(""""message":"breach","currentValue":150000.0,"threshold":100000.0,"bookId":"book-1",""")
            append(""""triggeredAt":"2025-01-15T10:00:00Z","status":"$status"""")
            if (escalatedTo != null) append(""","escalatedTo":"$escalatedTo","escalatedAt":"2025-01-15T10:05:00Z"""")
            if (resolvedReason != null) append(""","resolvedReason":"$resolvedReason","resolvedAt":"2025-01-15T10:10:00Z"""")
            append("}")
        }

    test("POST /api/v1/notifications/alerts/{id}/escalate forwards body to notification-service and returns 200 with ESCALATED alert") {
        val backend = BackendStubServer {
            post("/api/v1/notifications/alerts/alert-1/escalate") {
                val body = call.receiveText()
                val parsed = Json.parseToJsonElement(body).jsonObject
                parsed["reason"]?.jsonPrimitive?.content shouldBe "VaR persisted breach"
                parsed["assignee"]?.jsonPrimitive?.content shouldBe "risk-manager"
                call.respondText(
                    alertJson("alert-1", "ESCALATED", escalatedTo = "risk-manager"),
                    ContentType.Application.Json,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val notificationClient = HttpNotificationServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(notificationClient) }
                val response = client.post("/api/v1/notifications/alerts/alert-1/escalate") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"VaR persisted breach","assignee":"risk-manager"}""")
                }
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["id"]?.jsonPrimitive?.content shouldBe "alert-1"
                body["status"]?.jsonPrimitive?.content shouldBe "ESCALATED"
                body["escalatedTo"]?.jsonPrimitive?.content shouldBe "risk-manager"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/notifications/alerts/{id}/resolve forwards body to notification-service and returns 200 with RESOLVED alert") {
        val backend = BackendStubServer {
            post("/api/v1/notifications/alerts/alert-2/resolve") {
                val body = call.receiveText()
                val parsed = Json.parseToJsonElement(body).jsonObject
                parsed["resolutionText"]?.jsonPrimitive?.content shouldBe "Position trimmed"
                call.respondText(
                    alertJson("alert-2", "RESOLVED", resolvedReason = "Position trimmed"),
                    ContentType.Application.Json,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val notificationClient = HttpNotificationServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(notificationClient) }
                val response = client.post("/api/v1/notifications/alerts/alert-2/resolve") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"resolutionText":"Position trimmed"}""")
                }
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["id"]?.jsonPrimitive?.content shouldBe "alert-2"
                body["status"]?.jsonPrimitive?.content shouldBe "RESOLVED"
                body["resolvedReason"]?.jsonPrimitive?.content shouldBe "Position trimmed"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /alerts/{id}/escalate propagates 404 when notification-service returns NotFound") {
        val backend = BackendStubServer {
            post("/api/v1/notifications/alerts/missing/escalate") {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val notificationClient = HttpNotificationServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(notificationClient) }
                val response = client.post("/api/v1/notifications/alerts/missing/escalate") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"missing alert"}""")
                }
                response.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /alerts/{id}/escalate propagates 409 when notification-service returns Conflict") {
        val backend = BackendStubServer {
            post("/api/v1/notifications/alerts/already/escalate") {
                call.respond(HttpStatusCode.Conflict, "Alert is already escalated")
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val notificationClient = HttpNotificationServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(notificationClient) }
                val response = client.post("/api/v1/notifications/alerts/already/escalate") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"again"}""")
                }
                response.status shouldBe HttpStatusCode.Conflict
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /alerts/{id}/resolve propagates 400 when notification-service rejects body") {
        val backend = BackendStubServer {
            post("/api/v1/notifications/alerts/anything/resolve") {
                call.respond(HttpStatusCode.BadRequest, "resolutionText must not be blank")
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val notificationClient = HttpNotificationServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(notificationClient) }
                val response = client.post("/api/v1/notifications/alerts/anything/resolve") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"resolutionText":""}""")
                }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
