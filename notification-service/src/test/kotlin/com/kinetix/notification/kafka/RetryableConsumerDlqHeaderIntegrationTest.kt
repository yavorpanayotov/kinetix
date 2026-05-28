package com.kinetix.notification.kafka

import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.testsupport.kafka.KafkaTestSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotBeBlank
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID

/**
 * Integration test that verifies [RetryableConsumer] attaches all 7 failure-metadata
 * headers to the DLQ record when a poison message exhausts its retry budget.
 *
 * Uses a real Testcontainers Kafka broker via [KafkaTestSetup]. The test wires
 * [RetryableConsumer.process] directly (no higher-level consumer), so it exercises
 * the DLQ send path in isolation.
 *
 * Headers asserted:
 *   - x-failure-reason         — "<ExceptionSimpleClassName>: <first line of message>" (≤512 chars)
 *   - x-failure-stacktrace-hash — 12 lowercase hex chars (SHA-256 prefix)
 *   - x-original-topic          — the topic the record came from
 *   - x-original-partition      — decimal partition string
 *   - x-original-offset         — decimal offset string
 *   - x-retry-count             — decimal count matching maxRetries
 *   - x-first-seen-timestamp    — ISO-8601 UTC timestamp parseable as Instant
 *
 * Also asserts that headers already present on the original record (e.g. trace
 * context headers) are forwarded unchanged to the DLQ record.
 */
class RetryableConsumerDlqHeaderIntegrationTest : FunSpec({

    val bootstrapServers by lazy { KafkaTestSetup.start() }

    /**
     * Creates a [KafkaConsumer] that receives raw bytes for headers so we can
     * read them directly from the [ConsumerRecord].
     */
    fun createDlqWatcher(bootstrapServers: String): KafkaConsumer<String, String> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-header-watcher-${UUID.randomUUID()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        return KafkaConsumer(props)
    }

    fun Headers.utf8(name: String): String? =
        lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    test("DLQ record carries all 7 failure-metadata headers when retries are exhausted") {
        val topic = "test.dlq-headers-${UUID.randomUUID()}"
        val dlqTopic = "$topic.dlq"
        KafkaTestSetup.ensureTopic(bootstrapServers, topic)
        KafkaTestSetup.ensureTopic(bootstrapServers, dlqTopic)

        val dlqProducer = KafkaTestSetup.createProducer(bootstrapServers)
        val maxRetries = 2
        val retryableConsumer = RetryableConsumer(
            topic = topic,
            maxRetries = maxRetries,
            baseDelayMs = 5,
            dlqProducer = dlqProducer,
        )

        val partition = 0
        val offset = 42L
        val key = "poison-key"
        val value = """{"bad":"payload"}"""

        val beforeProcess = Instant.now()

        // Drive the consumer directly — no need for a real Kafka consumer loop
        // for this test; we care only about the DLQ record's headers.
        runCatching {
            retryableConsumer.process(
                key = key,
                value = value,
                partition = partition,
                offset = offset,
            ) {
                throw IllegalArgumentException("malformed payload: missing required field 'eventId'")
            }
        }

        val dlqWatcher = createDlqWatcher(bootstrapServers)
        dlqWatcher.subscribe(listOf(dlqTopic))

        var dlqRecord: org.apache.kafka.clients.consumer.ConsumerRecord<String, String>? = null
        assertTimeoutPreemptively(Duration.ofSeconds(15)) {
            while (dlqRecord == null) {
                val polled = dlqWatcher.poll(Duration.ofMillis(500))
                for (r in polled) {
                    dlqRecord = r
                }
            }
        }
        dlqWatcher.close()
        dlqProducer.close()

        val record = dlqRecord.shouldNotBeNull()

        // key and value must be preserved unchanged
        record.key() shouldBe key
        record.value() shouldBe value

        val headers = record.headers()

        // x-failure-reason: "<SimpleClassName>: <first line of message>" truncated to 512 chars
        val failureReason = headers.utf8("x-failure-reason").shouldNotBeNull()
        failureReason shouldMatch Regex("^IllegalArgumentException: .*")
        (failureReason.length <= 512) shouldBe true

        // x-failure-stacktrace-hash: exactly 12 lowercase hex chars
        val stackHash = headers.utf8("x-failure-stacktrace-hash").shouldNotBeNull()
        stackHash shouldMatch Regex("^[0-9a-f]{12}$")

        // x-original-topic
        headers.utf8("x-original-topic") shouldBe topic

        // x-original-partition
        headers.utf8("x-original-partition") shouldBe partition.toString()

        // x-original-offset
        headers.utf8("x-original-offset") shouldBe offset.toString()

        // x-retry-count: equals maxRetries (number of retries attempted before giving up)
        headers.utf8("x-retry-count") shouldBe maxRetries.toString()

        // x-first-seen-timestamp: parseable ISO-8601 UTC, not before we called process()
        val firstSeenRaw = headers.utf8("x-first-seen-timestamp").shouldNotBeNull()
        firstSeenRaw.shouldNotBeBlank()
        val firstSeen = Instant.parse(firstSeenRaw)
        firstSeen.isBefore(beforeProcess) shouldBe false
    }

    test("original record headers are forwarded unchanged to the DLQ record") {
        val topic = "test.dlq-header-forwarding-${UUID.randomUUID()}"
        val dlqTopic = "$topic.dlq"
        KafkaTestSetup.ensureTopic(bootstrapServers, topic)
        KafkaTestSetup.ensureTopic(bootstrapServers, dlqTopic)

        val dlqProducer = KafkaTestSetup.createProducer(bootstrapServers)
        val retryableConsumer = RetryableConsumer(
            topic = topic,
            maxRetries = 1,
            baseDelayMs = 5,
            dlqProducer = dlqProducer,
        )

        // Simulate original Kafka record headers (e.g. trace context headers)
        val originalHeaders = RecordHeaders().apply {
            add("x-b3-traceid", "abc123def456".toByteArray(Charsets.UTF_8))
            add("x-b3-spanid", "7890abcd".toByteArray(Charsets.UTF_8))
            add("x-correlation-id", "corr-99".toByteArray(Charsets.UTF_8))
        }

        runCatching {
            retryableConsumer.process(
                key = "trace-key",
                value = "trace-value",
                partition = 0,
                offset = 7L,
                originalHeaders = originalHeaders,
            ) {
                throw RuntimeException("permanent failure")
            }
        }

        val dlqWatcher = createDlqWatcher(bootstrapServers)
        dlqWatcher.subscribe(listOf(dlqTopic))

        var dlqRecord: org.apache.kafka.clients.consumer.ConsumerRecord<String, String>? = null
        assertTimeoutPreemptively(Duration.ofSeconds(15)) {
            while (dlqRecord == null) {
                val polled = dlqWatcher.poll(Duration.ofMillis(500))
                for (r in polled) {
                    dlqRecord = r
                }
            }
        }
        dlqWatcher.close()
        dlqProducer.close()

        val record = dlqRecord.shouldNotBeNull()
        val headers = record.headers()

        // Original trace context headers must be forwarded unchanged
        headers.utf8("x-b3-traceid") shouldBe "abc123def456"
        headers.utf8("x-b3-spanid") shouldBe "7890abcd"
        headers.utf8("x-correlation-id") shouldBe "corr-99"

        // The 7 failure-metadata headers are also present (smoke-check a subset)
        headers.utf8("x-original-topic") shouldBe topic
        headers.utf8("x-original-offset") shouldBe "7"
        headers.utf8("x-failure-reason").shouldNotBeNull()
    }
})
