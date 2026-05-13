package com.kinetix.audit.kafka

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.persistence.AuditEventRepository
import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.common.kafka.events.TradeEventMessage
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

class AuditEventConsumer(
    private val consumer: KafkaConsumer<String, String>,
    private val repository: AuditEventRepository,
    private val topic: String = "trades.lifecycle",
    private val retryableConsumer: RetryableConsumer = RetryableConsumer(topic = topic),
) {
    private val logger = LoggerFactory.getLogger(AuditEventConsumer::class.java)
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
                            val event = json.decodeFromString<TradeEventMessage>(record.value())
                            MDC.put("correlationId", event.correlationId ?: "")
                            try {
                                val auditEvent = event.toAuditEvent(receivedAt = Instant.now())
                                repository.save(auditEvent)
                                logger.info(
                                    "Audit event persisted: tradeId={}, bookId={}, eventType={}",
                                    auditEvent.tradeId, auditEvent.bookId, auditEvent.eventType,
                                )
                            } finally {
                                MDC.remove("correlationId")
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to process audit event after retries: offset={}, partition={}",
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
                logger.info("Closing audit event Kafka consumer")
                consumer.close(Duration.ofSeconds(10))
            }
        }
    }

    private fun TradeEventMessage.toAuditEvent(receivedAt: Instant): AuditEvent = AuditEvent(
        tradeId = tradeId,
        bookId = bookId,
        instrumentId = instrumentId,
        assetClass = assetClass,
        side = side,
        quantity = quantity,
        priceAmount = priceAmount,
        priceCurrency = priceCurrency,
        tradedAt = tradedAt,
        receivedAt = receivedAt,
        userId = userId,
        userRole = userRole,
        traderId = traderId,
        eventType = auditEventType,
    )
}
