package com.kinetix.gateway.routes

import io.github.smiley4.ktoropenapi.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.encodeURLQueryComponent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Query parameters the audit proxy forwards verbatim to the audit-service
 * `GET /api/v1/audit/events` endpoint. Filters (`bookId`, `tradeId`,
 * `eventType`, `from`, `to`) plus cursor pagination (`afterId`, `limit`).
 */
private val FORWARDED_AUDIT_EVENT_PARAMS = listOf(
    "bookId", "tradeId", "eventType", "from", "to", "afterId", "limit",
)

fun Route.auditProxyRoutes(httpClient: HttpClient, auditBaseUrl: String) {
    route("/api/v1/audit") {
        get("/events", {
            summary = "List audit events"
            tags = listOf("Audit")
            request {
                queryParameter<String>("bookId") { description = "Filter by book ID"; required = false }
                queryParameter<String>("tradeId") { description = "Filter by trade ID"; required = false }
                queryParameter<String>("eventType") { description = "Filter by event type"; required = false }
                queryParameter<String>("from") { description = "Start of time range (inclusive, ISO-8601)"; required = false }
                queryParameter<String>("to") { description = "End of time range (inclusive, ISO-8601)"; required = false }
                queryParameter<Long>("afterId") { description = "Cursor for pagination"; required = false }
                queryParameter<Int>("limit") { description = "Max events to return"; required = false }
            }
        }) {
            val queryString = call.request.queryParameters.let { params ->
                val parts = FORWARDED_AUDIT_EVENT_PARAMS.mapNotNull { name ->
                    params[name]?.let { "$name=${it.encodeURLQueryComponent()}" }
                }
                if (parts.isNotEmpty()) "?${parts.joinToString("&")}" else ""
            }
            val response = httpClient.get("$auditBaseUrl/api/v1/audit/events$queryString")
            val events: JsonArray = response.body()
            call.respond(events)
        }

        get("/verify", {
            summary = "Verify audit chain integrity"
            tags = listOf("Audit")
        }) {
            val response = httpClient.get("$auditBaseUrl/api/v1/audit/verify")
            val result: JsonObject = response.body()
            call.respond(result)
        }
    }
}
