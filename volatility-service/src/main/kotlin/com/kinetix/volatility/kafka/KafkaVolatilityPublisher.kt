package com.kinetix.volatility.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.model.VolSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaVolatilityPublisher(
    private val producer: KafkaProducer<String, String>,
) : VolatilityPublisher {

    private val logger = LoggerFactory.getLogger(KafkaVolatilityPublisher::class.java)

    override suspend fun publishSurface(surface: VolSurface) {
        val event = VolSurfaceEvent.from(surface)
        val json = Json.encodeToString(event)
        val record = ProducerRecord("volatility.surfaces", surface.instrumentId.value, json)
        send(record, "vol surface", surface.instrumentId.value)
    }

    private suspend fun send(record: ProducerRecord<String, String>, type: String, key: String) {
        val recordWithId = KafkaCorrelationIdHeaderWriter.withCorrelationId(record)
        try {
            withContext(Dispatchers.IO) {
                producer.send(recordWithId).get()
            }
        } catch (e: Exception) {
            logger.error("Failed to publish {} event to Kafka: key={}, topic={}", type, key, record.topic(), e)
        }
    }
}
