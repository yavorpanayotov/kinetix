package com.kinetix.risk.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.risk.model.RiskAuditEvent
import com.kinetix.risk.service.RiskAuditEventPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaRiskAuditPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.audit",
) : RiskAuditEventPublisher {

    private val logger = LoggerFactory.getLogger(KafkaRiskAuditPublisher::class.java)

    override suspend fun publish(event: RiskAuditEvent) {
        val key = "${event.bookId}:${event.jobId}"
        val json = Json.encodeToString(event)
        val record = KafkaCorrelationIdHeaderWriter.withCorrelationId(
            ProducerRecord(topic, key, json)
        )

        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
            logger.info(
                "Published risk audit event: type={}, jobId={}, manifestId={}",
                event.eventType, event.jobId, event.manifestId,
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to publish risk audit event: type={}, jobId={}, topic={}",
                event.eventType, event.jobId, topic, e,
            )
        }
    }
}
