package com.kinetix.correlation.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.model.CorrelationMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaCorrelationPublisher(
    private val producer: KafkaProducer<String, String>,
) : CorrelationPublisher {

    private val logger = LoggerFactory.getLogger(KafkaCorrelationPublisher::class.java)

    override suspend fun publish(matrix: CorrelationMatrix) {
        val event = CorrelationMatrixEvent.from(matrix)
        val json = Json.encodeToString(event)
        val key = matrix.labels.sorted().joinToString(",")
        val record = ProducerRecord("correlation.matrices", key, json)
        send(record, "correlation matrix", key)
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
