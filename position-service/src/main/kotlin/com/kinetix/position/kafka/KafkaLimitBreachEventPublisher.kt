package com.kinetix.position.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.events.LimitBreachEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaLimitBreachEventPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "limits.breaches",
) : LimitBreachEventPublisher {

    private val logger = LoggerFactory.getLogger(KafkaLimitBreachEventPublisher::class.java)

    override suspend fun publish(event: LimitBreachEvent) {
        val json = Json.encodeToString(event)
        val record = KafkaCorrelationIdHeaderWriter.withCorrelationId(
            ProducerRecord(topic, event.bookId, json)
        )

        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to publish limit-breach event to Kafka: eventId={}, bookId={}, topic={}",
                event.eventId, event.bookId, topic, e,
            )
        }
    }
}
