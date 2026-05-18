package com.kinetix.demo.kafka

import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.demo.schedule.EodCycleObserverJob
import com.kinetix.demo.schedule.OfficialEodPromotedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.coroutines.coroutineContext

/**
 * Kafka consumer for the `risk.official-eod` topic. Each event is deserialised
 * into [OfficialEodPromotedEvent] and dispatched to [EodCycleObserverJob.processEvent].
 *
 * Mirrors the shape of `notification-service`'s `RiskResultConsumer`:
 *  - subscribes to the topic on [start]
 *  - polls in 100ms intervals
 *  - each record is wrapped in a [RetryableConsumer.process] block so transient
 *    failures get exponential-backoff retries (and a DLQ send if configured)
 *  - offsets are committed synchronously after each non-empty batch
 *  - the underlying [KafkaConsumer] is closed in the `finally` block when the
 *    coroutine is cancelled, so shutdown is driven by the caller cancelling the
 *    `launch` that owns [start].
 */
class OfficialEodConsumer(
    private val consumer: KafkaConsumer<String, String>,
    private val job: EodCycleObserverJob,
    private val topic: String = "risk.official-eod",
    private val retryableConsumer: RetryableConsumer = RetryableConsumer(topic = topic),
) {
    private val logger = LoggerFactory.getLogger(OfficialEodConsumer::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun start() {
        withContext(Dispatchers.IO) {
            consumer.subscribe(listOf(topic))
        }
        logger.info("Subscribed to topic: {}", topic)
        try {
            while (coroutineContext.isActive) {
                val records = withContext(Dispatchers.IO) {
                    consumer.poll(Duration.ofMillis(POLL_INTERVAL_MS))
                }
                for (record in records) {
                    try {
                        retryableConsumer.process(record.key() ?: "", record.value()) {
                            val event = json.decodeFromString<OfficialEodPromotedEvent>(record.value())
                            logger.info(
                                "Dispatching OfficialEodPromotedEvent jobId={} bookId={} valuationDate={}",
                                event.jobId, event.bookId, event.valuationDate,
                            )
                            job.processEvent(event)
                        }
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to process official-eod event after retries: offset={}, partition={}",
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
                logger.info("Closing official-eod Kafka consumer")
                consumer.close(Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS))
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L
        const val CLOSE_TIMEOUT_SECONDS = 10L
    }
}
