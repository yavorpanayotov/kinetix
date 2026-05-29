package com.kinetix.common.observability

import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import org.apache.kafka.common.header.Headers

/**
 * Utilities for propagating OpenTelemetry trace context through Kafka record headers.
 *
 * Kafka does not have native trace context support, so we follow the W3C Trace Context
 * specification manually: the producer calls [inject] before publishing, embedding the
 * active span's `traceparent` (and optional `tracestate`) into the record's [Headers].
 * The consumer calls [extract] after receiving a record, recovering the [Context] so that
 * subsequent spans are linked to the original producer trace.
 *
 * This keeps the DLQ replay chain intact — [com.kinetix.common.kafka.RetryableConsumer]
 * already forwards original headers on DLQ records, so trace context survives retries.
 */
object OtelKafkaTracing {

    /**
     * Writes the W3C trace context from the given [context] into [headers].
     *
     * Call this on the producer side before sending a [org.apache.kafka.clients.producer.ProducerRecord]:
     * ```kotlin
     * val headers = RecordHeaders()
     * OtelKafkaTracing.inject(Context.current(), headers, openTelemetry.propagators)
     * producer.send(ProducerRecord(topic, null, key, value, headers))
     * ```
     *
     * @param context The current [Context] — typically [Context.current] inside an active span.
     * @param headers The mutable Kafka [Headers] to write into.
     * @param propagators The [ContextPropagators] from the service's [io.opentelemetry.api.OpenTelemetry] instance.
     */
    fun inject(context: Context, headers: Headers, propagators: ContextPropagators) {
        propagators.textMapPropagator.inject(context, headers, KafkaHeadersSetter)
    }

    /**
     * Reads the W3C trace context from [headers] and returns a child [Context].
     *
     * Call this on the consumer side immediately after receiving a record:
     * ```kotlin
     * val ctx = OtelKafkaTracing.extract(record.headers(), openTelemetry.propagators)
     * val span = openTelemetry.getTracer("my-consumer")
     *     .spanBuilder("process-event")
     *     .setParent(ctx)
     *     .startSpan()
     * ```
     *
     * @param headers The Kafka [Headers] from the consumed record.
     * @param propagators The [ContextPropagators] from the service's [io.opentelemetry.api.OpenTelemetry] instance.
     * @return A [Context] containing the span context extracted from the headers, or the
     *   root context if no valid trace context is found.
     */
    fun extract(headers: Headers, propagators: ContextPropagators): Context =
        propagators.textMapPropagator.extract(Context.root(), headers, KafkaHeadersGetter)
}

/**
 * [TextMapSetter] that writes a single key-value pair into Kafka [Headers].
 *
 * Overwrites any existing header with the same key to avoid stale context from
 * previously re-used [org.apache.kafka.common.header.internals.RecordHeaders] instances.
 */
private object KafkaHeadersSetter : TextMapSetter<Headers> {
    override fun set(carrier: Headers?, key: String, value: String) {
        carrier?.remove(key)
        carrier?.add(key, value.toByteArray(Charsets.UTF_8))
    }
}

/**
 * [TextMapGetter] that reads header values from Kafka [Headers].
 *
 * Returns the last header for a given key — consistent with Kafka's own multi-value header
 * semantics and the DLQ forwarding logic in [com.kinetix.common.kafka.RetryableConsumer].
 */
private object KafkaHeadersGetter : TextMapGetter<Headers> {

    override fun keys(carrier: Headers): Iterable<String> =
        carrier.map { it.key() }

    override fun get(carrier: Headers?, key: String): String? =
        carrier?.lastHeader(key)?.value()?.toString(Charsets.UTF_8)
}
