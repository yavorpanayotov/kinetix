package com.kinetix.gateway.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray

/**
 * Gateway proxy routes for AI insight endpoints.
 *
 * Forwards `POST /api/v1/insights/explain/var` (PR 2 — VaR Explainer) and
 * `POST /api/v1/insights/explain/report` (PR 3 — Risk Report Generator) to
 * the `ai-insights-service` backend without transforming the body. The
 * gateway does not own the insight schema — request and response bodies
 * pass through as raw bytes so downstream schema evolution does not
 * require redeploying the gateway.
 *
 * The configured `insightsBaseUrl` is sourced from `services.insights.url` in
 * `application.conf`, which honours the `INSIGHTS_SERVICE_URL` env override.
 */
fun Route.insightsRoutes(httpClient: HttpClient, insightsBaseUrl: String) {
    post("/api/v1/insights/explain/var") {
        proxyToInsights(httpClient, "$insightsBaseUrl/api/v1/insights/explain/var", call)
    }
    post("/api/v1/insights/explain/report") {
        proxyToInsights(httpClient, "$insightsBaseUrl/api/v1/insights/explain/report", call)
    }
}

private suspend fun proxyToInsights(
    httpClient: HttpClient,
    upstreamUrl: String,
    call: ApplicationCall,
) {
    val method = call.request.httpMethod
    val requestBody: ByteArray? = if (method == HttpMethod.Post || method == HttpMethod.Put) {
        call.receiveChannel().toByteArray()
    } else null

    val response = httpClient.request(upstreamUrl) {
        this.method = method
        if (requestBody != null) {
            contentType(call.request.contentType())
            setBody(requestBody)
        }
        call.request.headers.forEach { name, values ->
            if (name !in setOf(HttpHeaders.Host, HttpHeaders.ContentLength)) {
                values.forEach { value -> header(name, value) }
            }
        }
    }

    call.respondBytes(
        bytes = response.readRawBytes(),
        contentType = response.contentType(),
        status = response.status,
    )
}
