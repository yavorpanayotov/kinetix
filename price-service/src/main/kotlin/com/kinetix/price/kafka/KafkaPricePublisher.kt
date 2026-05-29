package com.kinetix.price.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.events.PriceEvent
import com.kinetix.common.model.PricePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaPricePublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "price.updates",
) : PricePublisher {

    private val logger = LoggerFactory.getLogger(KafkaPricePublisher::class.java)

    override suspend fun publish(point: PricePoint) {
        val event = PriceEvent.from(point)
        val json = Json.encodeToString(event)
        val record = KafkaCorrelationIdHeaderWriter.withCorrelationId(
            ProducerRecord(topic, point.instrumentId.value, json)
        )

        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to publish price event to Kafka: instrumentId={}, topic={}",
                point.instrumentId.value, topic, e,
            )
        }
    }
}
