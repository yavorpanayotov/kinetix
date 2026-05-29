package com.kinetix.gateway.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.response.respond
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Registers the aggregated system-health endpoint at GET /api/v1/system/health.
 *
 * The route is public (no JWT challenge) so CI/k8s probes can reach it
 * without a token. It fan-outs health checks to all downstream services
 * concurrently and returns a JSON summary with an overall status of UP or
 * DEGRADED.
 */
fun Route.systemHealthRoutes(
    httpClient: HttpClient,
    serviceUrls: Map<String, String>,
) {
    get("/api/v1/system/health") {
        val results = coroutineScope {
            serviceUrls.map { (name, url) ->
                name to async {
                    try {
                        val resp = withTimeoutOrNull(5000L) {
                            httpClient.get("$url/health/ready")
                        }
                        if (resp != null && resp.status == HttpStatusCode.OK) "READY" else "NOT_READY"
                    } catch (_: Exception) {
                        "DOWN"
                    }
                }
            }.map { (name, deferred) -> name to deferred.await() }
        }
        val overall = if (results.all { it.second == "READY" }) "UP" else "DEGRADED"
        val response = buildJsonObject {
            put("status", overall)
            putJsonObject("services") {
                putJsonObject("gateway") { put("status", "READY") }
                for ((name, status) in results) {
                    putJsonObject(name) { put("status", status) }
                }
            }
        }
        call.respond(response)
    }
}
