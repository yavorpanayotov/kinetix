package com.kinetix.common.kafka

import com.kinetix.common.observability.CorrelationIdContext
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.MDC

/**
 * Utility that enriches outbound Kafka [ProducerRecord]s with the current
 * correlation ID as a record header.
 *
 * Usage in a Kafka publisher:
 * ```kotlin
 * val record = ProducerRecord(topic, key, json)
 * producer.send(KafkaCorrelationIdHeaderWriter.withCorrelationId(record))
 * ```
 *
 * If there is no correlation ID in the current MDC the record is returned
 * unchanged. Existing headers are always preserved.
 */
object KafkaCorrelationIdHeaderWriter {

    /**
     * Returns [record] with an `X-Correlation-ID` header appended when the
     * current MDC contains a correlation ID, or [record] unmodified otherwise.
     */
    fun <K, V> withCorrelationId(record: ProducerRecord<K, V>): ProducerRecord<K, V> {
        val id = MDC.get(CorrelationIdContext.MDC_KEY) ?: return record
        record.headers().add(
            CorrelationIdContext.HEADER_NAME,
            id.toByteArray(Charsets.UTF_8),
        )
        return record
    }
}
