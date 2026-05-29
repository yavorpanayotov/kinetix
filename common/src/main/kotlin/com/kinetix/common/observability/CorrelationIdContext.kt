package com.kinetix.common.observability

import org.slf4j.MDC
import java.util.UUID

/**
 * Single source of truth for correlation ID constants and MDC helpers.
 *
 * The correlation ID is a stable, user-facing request identifier that survives
 * async boundaries (HTTP headers, gRPC metadata, Kafka record headers) and is
 * returned in API responses. It complements the OpenTelemetry trace ID (W3C
 * traceparent), which is managed independently by the OTel SDK.
 *
 * MDC key:  "correlationId"
 * Header:   "X-Correlation-ID"  (HTTP + Kafka)
 * gRPC key: "x-correlation-id"  (lowercase — gRPC metadata is case-folded)
 */
object CorrelationIdContext {

    /** SLF4J MDC key used across all Kinetix services. */
    const val MDC_KEY = "correlationId"

    /** HTTP request/response header name (HTTP + Kafka record header). */
    const val HEADER_NAME = "X-Correlation-ID"

    /** gRPC metadata key (lowercase per gRPC convention). */
    const val GRPC_METADATA_KEY = "x-correlation-id"

    /** Returns the current correlation ID from MDC, or null if none is set. */
    fun current(): String? = MDC.get(MDC_KEY)

    /** Generates a new correlation ID as a random UUID string. */
    fun generate(): String = UUID.randomUUID().toString()

    /**
     * Runs [block] with [correlationId] installed in MDC, then restores the
     * previous value (or removes the key if there was none).
     *
     * Safe to nest: the previous value is always restored, even if [block] throws.
     */
    inline fun <T> runWithCorrelationId(correlationId: String, block: () -> T): T {
        val previous = MDC.get(MDC_KEY)
        MDC.put(MDC_KEY, correlationId)
        try {
            return block()
        } finally {
            if (previous != null) {
                MDC.put(MDC_KEY, previous)
            } else {
                MDC.remove(MDC_KEY)
            }
        }
    }
}
