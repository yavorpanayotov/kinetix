package com.kinetix.gateway.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.copyAndClose
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

/**
 * SSE-aware proxy: streams the upstream response body byte-for-byte to the
 * client without buffering the full payload. Used for Server-Sent Events
 * endpoints (e.g. `POST /api/v1/insights/chat`) where the upstream emits
 * frames over a long-lived connection and the UI must receive each frame as
 * soon as the upstream produces it.
 *
 * Mechanism: `httpClient.prepareRequest { … }.execute { response -> … }` is
 * the Ktor `HttpStatement` pattern that exposes the streaming response body
 * without coalescing it into a single byte array. `bodyAsChannel()` returns
 * the raw `ByteReadChannel`, and `respondBytesWriter` opens a downstream
 * `ByteWriteChannel` writer. `copyAndClose` shovels bytes through, flushing
 * after every read so SSE frames reach the client in real time. The body
 * accumulator path in `proxyToInsights` (which calls `readRawBytes`) is
 * deliberately avoided here — it would block until the upstream connection
 * closed, defeating streaming.
 *
 * The buffered `proxyToInsights` remains the right tool for the v1 explainer
 * routes, which return a single JSON payload.
 */
internal suspend fun streamProxyToInsights(
    httpClient: HttpClient,
    upstreamUrl: String,
    call: ApplicationCall,
) {
    val method = call.request.httpMethod
    val requestBody: ByteArray? = if (method == HttpMethod.Post || method == HttpMethod.Put) {
        call.receiveChannel().toByteArray()
    } else null

    httpClient.prepareRequest(upstreamUrl) {
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
    }.execute { response ->
        call.respondBytesWriter(
            contentType = response.contentType(),
            status = response.status,
        ) {
            response.bodyAsChannel().copyAndClose(this)
        }
    }
}
