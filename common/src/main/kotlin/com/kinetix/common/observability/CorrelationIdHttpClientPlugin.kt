package com.kinetix.common.observability

import io.ktor.client.plugins.api.*
import org.slf4j.MDC

/**
 * Ktor HTTP client plugin that propagates the current MDC correlation ID to
 * outbound HTTP requests as the `X-Correlation-ID` header.
 *
 * If the caller has already set `X-Correlation-ID` on the request, the plugin
 * leaves it untouched. This allows specific call sites to override the ID when
 * needed (e.g. for fan-out requests that start a new correlation scope).
 *
 * If there is no current MDC value, no header is added.
 *
 * Install on every shared `HttpClient` in gateway and risk-orchestrator:
 * ```kotlin
 * val httpClient = HttpClient(CIO) {
 *     install(CorrelationIdHttpClientPlugin)
 *     // ...
 * }
 * ```
 */
val CorrelationIdHttpClientPlugin: ClientPlugin<Unit> = createClientPlugin(
    name = "CorrelationIdHttpClientPlugin",
) {
    onRequest { request, _ ->
        val current = MDC.get(CorrelationIdContext.MDC_KEY) ?: return@onRequest
        // Do not override if the caller already set the header
        if (request.headers[CorrelationIdContext.HEADER_NAME] == null) {
            request.headers.append(CorrelationIdContext.HEADER_NAME, current)
        }
    }
}
