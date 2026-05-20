package com.kinetix.audit.kafka

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.persistence.AuditEventRepository
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.common.kafka.RetryableConsumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.coroutineContext

class GovernanceAuditEventConsumer(
    private val consumer: KafkaConsumer<String, String>,
    private val repository: AuditEventRepository,
    private val topic: String = "governance.audit",
    private val retryableConsumer: RetryableConsumer = RetryableConsumer(topic = topic),
) {
    private val logger = LoggerFactory.getLogger(GovernanceAuditEventConsumer::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun start() {
        withContext(Dispatchers.IO) {
            consumer.subscribe(listOf(topic))
        }
        try {
            while (coroutineContext.isActive) {
                val records = withContext(Dispatchers.IO) {
                    consumer.poll(Duration.ofMillis(100))
                }
                for (record in records) {
                    try {
                        retryableConsumer.process(record.key() ?: "", record.value()) {
                            val event = json.decodeFromString<GovernanceAuditEvent>(record.value())
                            MDC.put("correlationId", event.correlationId ?: "")
                            try {
                                val auditEvent = event.toAuditEvent(
                                    receivedAt = Instant.now(),
                                    sourceTopic = record.topic(),
                                    sourcePartition = record.partition(),
                                    sourceOffset = record.offset(),
                                )
                                repository.save(auditEvent)
                                logger.info(
                                    "Governance audit event persisted: eventType={}, userId={}, userRole={}",
                                    auditEvent.eventType, auditEvent.userId, auditEvent.userRole,
                                )
                            } finally {
                                MDC.remove("correlationId")
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to process governance audit event after retries: offset={}, partition={}",
                            record.offset(), record.partition(), e,
                        )
                    }
                }
                if (!records.isEmpty) {
                    withContext(Dispatchers.IO) { consumer.commitSync() }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                logger.info("Closing governance audit event Kafka consumer")
                consumer.close(Duration.ofSeconds(10))
            }
        }
    }

    private fun GovernanceAuditEvent.toAuditEvent(
        receivedAt: Instant,
        sourceTopic: String,
        sourcePartition: Int,
        sourceOffset: Long,
    ): AuditEvent = AuditEvent(
        tradeId = null,
        bookId = bookId,
        instrumentId = null,
        assetClass = null,
        side = null,
        quantity = null,
        priceAmount = null,
        priceCurrency = null,
        tradedAt = null,
        receivedAt = receivedAt,
        userId = userId,
        userRole = userRole,
        eventType = eventType.name,
        modelName = modelName,
        scenarioId = scenarioId,
        limitId = limitId,
        submissionId = submissionId,
        details = details,
        correlationId = correlationId,
        sourceTopic = sourceTopic,
        sourcePartition = sourcePartition,
        sourceOffset = sourceOffset,
    )
}
