package com.kinetix.fix.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.execution.ExecutionReportEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

/**
 * Kafka-backed [ExecutionReportPublisher].
 *
 * Partition key is `clOrdId`. When `clOrdId` is empty (e.g. 35=j BusinessMessageReject
 * without a referencing ClOrdID, or a malformed inbound 35=8 with no tag 11), the publisher
 * falls back to `venue` so the message still partitions deterministically — no events are
 * dropped, and downstream parity monitoring (`malformed_inbound_total{venue, defect}`) can
 * decide what to do.
 *
 * The producer wiring (idempotence, acks=all, in-flight cap, delivery timeout) lives in
 * [ExecutionReportProducerFactory]. The publisher itself is intentionally thin so tests can
 * substitute their own producer.
 */
class KafkaExecutionReportPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "execution.reports",
) : ExecutionReportPublisher {

    private val logger = LoggerFactory.getLogger(KafkaExecutionReportPublisher::class.java)

    override suspend fun publish(event: ExecutionReportEvent) {
        val payload = Json.encodeToString(ExecutionReportEvent.serializer(), event)
        val partitionKey = event.clOrdId.ifEmpty { event.venue ?: "UNKNOWN" }
        val record = KafkaCorrelationIdHeaderWriter.withCorrelationId(
            ProducerRecord(topic, partitionKey, payload)
        )
        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
            logger.info(
                "ExecutionReportEvent published: eventId={} eventType={} clOrdId={} venue={} execId={}",
                event.eventId, event.eventType, event.clOrdId, event.venue, event.execId,
            )
        } catch (e: Exception) {
            // Reraise so the FIX session callback can decide what to do (e.g. NACK and let
            // the venue re-send on logon). Silently swallowing here would lose events.
            logger.error(
                "Failed to publish ExecutionReportEvent eventId={} clOrdId={}: {}",
                event.eventId, event.clOrdId, e.message,
            )
            throw e
        }
    }
}
