package com.kinetix.common.observability

import io.ktor.server.application.*
import io.ktor.server.request.*
import org.slf4j.MDC

/**
 * Ktor server plugin that ensures every inbound HTTP request has a correlation ID
 * in MDC and echoes it back in the response `X-Correlation-ID` header.
 *
 * Behaviour:
 * - If `X-Correlation-ID` is present in the inbound request, that value is used.
 * - If absent, a fresh UUID is generated via [CorrelationIdContext.generate].
 * - The resolved ID is placed in MDC under [CorrelationIdContext.MDC_KEY].
 * - The resolved ID is written to the response header `X-Correlation-ID`.
 *
 * The existing `CallLogging { mdc("correlationId") { ... } }` blocks in each
 * service's `Application.module()` already extract the ID into MDC for the log
 * message. This plugin adds the response echo-back, which CallLogging does not do.
 * The two are complementary: CallLogging owns the log-time MDC snapshot; this
 * plugin owns the response header and makes the lifecycle-scoped MDC value visible
 * to downstream infrastructure (e.g. gRPC client interceptors, Kafka producers)
 * invoked during request processing.
 */
val CorrelationIdHttpServerPlugin: ApplicationPlugin<Unit> = createApplicationPlugin(
    name = "CorrelationIdHttpServerPlugin",
) {
    onCall { call ->
        val id = call.request.header(CorrelationIdContext.HEADER_NAME)
            ?: CorrelationIdContext.generate()
        MDC.put(CorrelationIdContext.MDC_KEY, id)
        call.response.headers.append(CorrelationIdContext.HEADER_NAME, id)
    }
}
