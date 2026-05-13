package com.kinetix.gateway.routes

import io.github.smiley4.ktoropenapi.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class TraderResponse(
    val id: String,
    val name: String,
    val deskId: String,
    val email: String? = null,
    val notionalLimitUsd: String? = null,
)

/**
 * Phase 3 Gap 13 — gateway pass-through for the trader catalogue, used by
 * the UI's per-trader drill-down entry points in the positions, P&L, and
 * risk tabs.
 */
fun Route.traderRoutes(httpClient: HttpClient, referenceDataBaseUrl: String) {
    route("/api/v1/traders") {
        get({
            summary = "List all traders"
            tags = listOf("Traders")
        }) {
            val response = httpClient.get("$referenceDataBaseUrl/api/v1/traders")
            val traders: List<TraderResponse> = response.body()
            call.respond(traders)
        }

        route("/{id}") {
            get({
                summary = "Get trader by ID"
                tags = listOf("Traders")
                request { pathParameter<String>("id") { description = "Trader identifier" } }
            }) {
                val id = call.requirePathParam("id")
                val response = httpClient.get("$referenceDataBaseUrl/api/v1/traders/$id")
                if (response.status == HttpStatusCode.NotFound) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    val trader: TraderResponse = response.body()
                    call.respond(trader)
                }
            }
        }
    }

    route("/api/v1/desks/{deskId}/traders") {
        get({
            summary = "List traders on a desk"
            tags = listOf("Traders")
            request { pathParameter<String>("deskId") { description = "Desk identifier" } }
        }) {
            val deskId = call.requirePathParam("deskId")
            val response = httpClient.get("$referenceDataBaseUrl/api/v1/desks/$deskId/traders")
            val traders: List<TraderResponse> = response.body()
            call.respond(traders)
        }
    }
}
