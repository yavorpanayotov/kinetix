package com.kinetix.risk.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.risk.model.OfficialEodPromotedEvent
import com.kinetix.risk.service.OfficialEodEventPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaOfficialEodPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.official-eod",
) : OfficialEodEventPublisher {

    private val logger = LoggerFactory.getLogger(KafkaOfficialEodPublisher::class.java)

    override suspend fun publish(event: OfficialEodPromotedEvent) {
        val key = "${event.bookId}:${event.valuationDate}"
        val json = Json.encodeToString(event)
        val record = KafkaCorrelationIdHeaderWriter.withCorrelationId(
            ProducerRecord(topic, key, json)
        )

        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to publish Official EOD event: bookId={}, valuationDate={}, topic={}",
                event.bookId, event.valuationDate, topic, e,
            )
        }
    }
}
