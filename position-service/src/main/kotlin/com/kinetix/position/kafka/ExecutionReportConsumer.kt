package com.kinetix.position.kafka

import com.kinetix.common.kafka.ConsumerLivenessTracker
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Subscribes to the `execution.reports` Kafka topic published by `fix-gateway`
 * (ADR-0035 phase 3). Each record is decoded by [ExecutionReportDispatcher] and
 * fed to the existing [com.kinetix.position.fix.FIXExecutionReportProcessor] —
 * the processor is unchanged because the wire schema carries the raw FIX tag
 * 150 `execType` field-for-field.
 *
 * Offset-commit semantics:
 *   - Commits per-partition AFTER the dispatcher returns successfully.
 *   - On dispatch failure, the offset is NOT committed; the next poll re-reads
 *     the same record. The dispatcher's own LRU plus the
 *     `uq_execution_fill_exec_id` UNIQUE constraint guarantee that the
 *     re-read does not produce a duplicate fill in the DB.
 *   - Malformed payloads are committed (the dispatcher counts and drops them)
 *     so a poison pill cannot block consumer progress.
 *
 * Liveness:
 *   - [ConsumerLivenessTracker] surfaces "saw a record" / "processed a record"
 *     to readiness probes. A consumer that has processed nothing for >60s is
 *     not necessarily unhealthy (low-volume venues), but lag growth is detected
 *     via Kafka consumer-group offsets in Grafana.
 */
class ExecutionReportConsumer(
    private val consumer: KafkaConsumer<String, String>,
    private val dispatcher: ExecutionReportDispatcher,
    private val livenessTracker: ConsumerLivenessTracker,
    private val meterRegistry: MeterRegistry,
    private val topic: String = "execution.reports",
    private val pollTimeout: Duration = Duration.ofMillis(500),
) {
    private val logger = LoggerFactory.getLogger(ExecutionReportConsumer::class.java)

    suspend fun start() {
        withContext(Dispatchers.IO) {
            consumer.subscribe(listOf(topic))
        }
        logger.info("ExecutionReportConsumer subscribed to topic={}", topic)
        try {
            while (currentCoroutineContext().isActive) {
                val records = withContext(Dispatchers.IO) {
                    consumer.poll(pollTimeout)
                }
                for (record in records) {
                    try {
                        dispatcher.dispatchRaw(
                            payload = record.value(),
                            offset = record.offset(),
                            partition = record.partition(),
                            key = record.key(),
                        )
                        livenessTracker.recordSuccess()
                        commitOffset(record.topic(), record.partition(), record.offset())
                    } catch (e: Exception) {
                        livenessTracker.recordError()
                        meterRegistry.counter(
                            "execution_report_consumer_processing_failed_total",
                            "topic", record.topic(),
                        ).increment()
                        logger.error(
                            "Failed to process ExecutionReportEvent — withholding offset commit so the record is replayed: " +
                                "partition={} offset={} key={}",
                            record.partition(), record.offset(), record.key(), e,
                        )
                        // Withhold commit — the next poll will replay this record.
                        // Idempotency at dispatcher.dispatch and at the DB unique
                        // constraint make replay safe.
                        break
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                logger.info("Closing execution.reports Kafka consumer")
                consumer.close(Duration.ofSeconds(10))
            }
        }
    }

    private suspend fun commitOffset(topic: String, partition: Int, offset: Long) {
        withContext(Dispatchers.IO) {
            consumer.commitSync(
                mapOf(TopicPartition(topic, partition) to OffsetAndMetadata(offset + 1)),
            )
        }
    }
}
