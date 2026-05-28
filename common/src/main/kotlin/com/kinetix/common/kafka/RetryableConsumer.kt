package com.kinetix.common.kafka

import kotlinx.coroutines.delay
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant

class RetryableConsumer(
    private val topic: String,
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000,
    private val dlqProducer: KafkaProducer<String, String>? = null,
    private val livenessTracker: ConsumerLivenessTracker? = null,
) {
    private val logger = LoggerFactory.getLogger(RetryableConsumer::class.java)
    private val dlqTopic = "$topic.dlq"

    suspend fun <T> process(
        key: String,
        value: String,
        partition: Int? = null,
        offset: Long? = null,
        originalHeaders: Headers? = null,
        handler: suspend () -> T,
    ): T {
        val firstSeenTimestamp = Instant.now()
        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                val result = handler()
                livenessTracker?.recordSuccess()
                return result
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    val delayMs = baseDelayMs * (1L shl attempt)
                    logger.warn(
                        "Retry {}/{} for topic={}, key={}, delaying {}ms",
                        attempt + 1, maxRetries, topic, key, delayMs,
                    )
                    delay(delayMs)
                }
            }
        }

        if (dlqProducer != null) {
            logger.error(
                "Max retries exhausted for topic={}, key={}. Sending to DLQ topic={}",
                topic, key, dlqTopic,
            )
            try {
                val dlqRecord = buildDlqRecord(
                    key = key,
                    value = value,
                    partition = partition,
                    offset = offset,
                    originalHeaders = originalHeaders,
                    exception = lastException!!,
                    retryCount = maxRetries,
                    firstSeenTimestamp = firstSeenTimestamp,
                )
                dlqProducer.send(dlqRecord)
                livenessTracker?.recordDlqSend()
            } catch (dlqException: Exception) {
                logger.error(
                    "DLQ send failed for topic={}, key={}, value={}. Message cannot be recovered via DLQ.",
                    topic, key, value, dlqException,
                )
            }
        } else {
            logger.error(
                "Max retries exhausted for topic={}, key={}. No DLQ producer configured.",
                topic, key,
            )
        }

        livenessTracker?.recordError()
        throw lastException!!
    }

    private fun buildDlqRecord(
        key: String,
        value: String,
        partition: Int?,
        offset: Long?,
        originalHeaders: Headers?,
        exception: Exception,
        retryCount: Int,
        firstSeenTimestamp: Instant,
    ): ProducerRecord<String, String> {
        val headers = RecordHeaders()

        // Forward original headers so distributed tracing survives DLQ replay.
        originalHeaders?.forEach { header -> headers.add(header) }

        // Attach failure-metadata headers (UTF-8 strings).
        headers.add("x-failure-reason", failureReason(exception).toUtf8())
        headers.add("x-failure-stacktrace-hash", stacktraceHash(exception).toUtf8())
        headers.add("x-original-topic", topic.toUtf8())
        if (partition != null) headers.add("x-original-partition", partition.toString().toUtf8())
        if (offset != null) headers.add("x-original-offset", offset.toString().toUtf8())
        headers.add("x-retry-count", retryCount.toString().toUtf8())
        headers.add("x-first-seen-timestamp", firstSeenTimestamp.toString().toUtf8())

        return ProducerRecord(dlqTopic, null, key, value, headers)
    }

    private fun failureReason(e: Exception): String {
        val simpleName = e.javaClass.simpleName
        val firstLine = e.message?.lines()?.firstOrNull()?.trim() ?: ""
        val reason = if (firstLine.isNotEmpty()) "$simpleName: $firstLine" else simpleName
        return reason.take(512)
    }

    private fun stacktraceHash(e: Exception): String {
        val trace = e.stackTraceToString()
        val digest = MessageDigest.getInstance("SHA-256").digest(trace.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun String.toUtf8(): ByteArray = toByteArray(Charsets.UTF_8)
}
