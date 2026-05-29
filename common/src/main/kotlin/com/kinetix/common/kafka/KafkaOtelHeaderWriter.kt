package com.kinetix.common.kafka

import com.kinetix.common.observability.OtelKafkaTracing
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import org.apache.kafka.clients.producer.ProducerRecord

/**
 * Enriches outbound Kafka [ProducerRecord]s with the current OpenTelemetry
 * span context as a `traceparent` W3C record header.
 *
 * Works in tandem with [KafkaCorrelationIdHeaderWriter]: the correlation-ID
 * header carries the request-scoped ID for log correlation; this class carries
 * the W3C trace context for distributed tracing. Both are applied to every
 * outbound record in the standard Kafka publisher implementations.
 *
 * Usage in a Kafka publisher:
 * ```kotlin
 * val record = KafkaCorrelationIdHeaderWriter.withCorrelationId(
 *     ProducerRecord(topic, key, json)
 * )
 * KafkaOtelHeaderWriter.injectTraceContext(record, openTelemetry)
 * producer.send(record)
 * ```
 *
 * When no span is active the W3C propagator injects nothing — no stale
 * context leaks into records produced outside a trace boundary.
 */
object KafkaOtelHeaderWriter {

    /**
     * Injects the active OpenTelemetry span context into [record]'s headers
     * as a W3C `traceparent` (and optional `tracestate`) header.
     *
     * @param record The [ProducerRecord] whose headers will be enriched.
     * @param openTelemetry The [OpenTelemetry] instance from which to obtain
     *   the propagators. When [openTelemetry] is [OpenTelemetry.noop] (the
     *   default when no OTLP endpoint is configured), this call is a no-op.
     * @return The same [record] instance, with trace headers added.
     */
    fun <K, V> injectTraceContext(
        record: ProducerRecord<K, V>,
        openTelemetry: OpenTelemetry,
    ): ProducerRecord<K, V> {
        OtelKafkaTracing.inject(Context.current(), record.headers(), openTelemetry.propagators)
        return record
    }
}
