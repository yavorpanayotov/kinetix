package com.kinetix.position.kafka

import com.kinetix.common.kafka.events.RiskBreakEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

/**
 * Publishes [RiskBreakEvent]s to `risk.breaks`. Partition key is the order
 * id (or `breakType` when no order id is set) so events for the same order
 * land on the same partition and consumers can dedup deterministically.
 */
class KafkaRiskBreakPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.breaks",
) : RiskBreakPublisher {

    private val logger = LoggerFactory.getLogger(KafkaRiskBreakPublisher::class.java)

    override suspend fun publish(event: RiskBreakEvent) {
        val key = event.orderId ?: event.breakType
        val record = ProducerRecord(topic, key, Json.encodeToString(event))
        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to publish risk-break event to Kafka: eventId={}, breakType={}, severity={}, topic={}",
                event.eventId, event.breakType, event.severity, topic, e,
            )
        }
    }
}
