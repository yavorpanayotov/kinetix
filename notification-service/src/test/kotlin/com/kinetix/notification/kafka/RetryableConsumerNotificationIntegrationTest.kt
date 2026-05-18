package com.kinetix.notification.kafka

import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.common.kafka.events.LimitBreachEvent
import com.kinetix.notification.delivery.DeliveryService
import com.kinetix.notification.model.AlertEvent
import com.kinetix.notification.model.DeliveryChannel
import com.kinetix.notification.persistence.InMemoryAlertEventRepository
import com.kinetix.testsupport.kafka.KafkaTestSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration test for the wiring between [LimitBreachEventConsumer] and
 * [RetryableConsumer] using a real Testcontainers Kafka broker.
 *
 * Unlike [NotificationConsumerDlqIntegrationTest] (which covers the
 * malformed-JSON path where the handler body throws on every attempt) and
 * [LimitBreachEventConsumerIntegrationTest] (which covers the consumer-loop
 * survival of bad payloads), this test exercises the retry mechanics around
 * collaborator-side failures — the more common production case:
 *
 *   1. **Transient errors → eventual success after retries.** A
 *      [DeliveryService] fake fails the first N attempts (N < maxRetries)
 *      and then succeeds. The valid [LimitBreachEvent] is eventually
 *      processed — the repository is populated and the delivery service is
 *      ultimately invoked successfully.
 *   2. **Permanent errors → DLQ.** A [DeliveryService] fake throws on every
 *      attempt. After [RetryableConsumer.maxRetries] is exhausted, the
 *      original key/value lands on the configured `<topic>.dlq` topic.
 */
class RetryableConsumerNotificationIntegrationTest : FunSpec({

    val bootstrapServers by lazy { KafkaTestSetup.start() }

    /**
     * Delivery service that fails its first [failuresBeforeSuccess] invocations
     * with a transient [RuntimeException] and then succeeds, recording every
     * successfully delivered [AlertEvent].
     */
    class FlakyDeliveryService(
        private val failuresBeforeSuccess: Int,
    ) : DeliveryService {
        val attemptCount = AtomicInteger(0)
        val delivered = CopyOnWriteArrayList<AlertEvent>()
        override val channel: DeliveryChannel = DeliveryChannel.IN_APP

        override suspend fun deliver(event: AlertEvent) {
            val attempt = attemptCount.incrementAndGet()
            if (attempt <= failuresBeforeSuccess) {
                throw RuntimeException("transient delivery failure (attempt=$attempt)")
            }
            delivered.add(event)
        }
    }

    /**
     * Delivery service whose [deliver] always throws — used to drive the
     * RetryableConsumer past its retry budget and into the DLQ branch.
     */
    class AlwaysFailingDeliveryService : DeliveryService {
        val attemptCount = AtomicInteger(0)
        override val channel: DeliveryChannel = DeliveryChannel.IN_APP

        override suspend fun deliver(event: AlertEvent) {
            attemptCount.incrementAndGet()
            throw RuntimeException("permanent delivery failure")
        }
    }

    fun buildEvent(bookId: String, message: String) = LimitBreachEvent(
        eventId = UUID.randomUUID().toString(),
        bookId = bookId,
        limitType = "VAR_95",
        severity = "HARD",
        currentValue = "1200000",
        limitValue = "1000000",
        message = message,
        breachedAt = "2025-01-15T10:00:00Z",
        tradeId = "trade-1",
        correlationId = "corr-1",
    )

    test("transient delivery failures retry and eventually succeed without DLQ publish") {
        val topic = "alerts.retry-test-${UUID.randomUUID()}"
        val dlqTopic = "$topic.dlq"
        KafkaTestSetup.ensureTopic(bootstrapServers, topic)
        KafkaTestSetup.ensureTopic(bootstrapServers, dlqTopic)

        val repository = InMemoryAlertEventRepository()
        // Fails on attempt 1, succeeds on attempt 2 — well within maxRetries=2
        // (which permits attempts 0, 1, 2 — i.e. one initial call + two retries).
        val deliveryService = FlakyDeliveryService(failuresBeforeSuccess = 1)

        val dlqProducer = KafkaTestSetup.createProducer(bootstrapServers)
        val retryableConsumer = RetryableConsumer(
            topic = topic,
            maxRetries = 2,
            baseDelayMs = 10,
            dlqProducer = dlqProducer,
        )
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "retry-transient-${UUID.randomUUID()}")
        val consumer = LimitBreachEventConsumer(
            consumer = kafkaConsumer,
            deliveryService = deliveryService,
            eventRepository = repository,
            topic = topic,
            retryableConsumer = retryableConsumer,
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch { consumer.start() }

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val dlqWatcher = KafkaTestSetup.createConsumer(
            bootstrapServers,
            "retry-transient-dlq-watcher-${UUID.randomUUID()}",
        )
        try {
            val event = buildEvent(bookId = "book-transient", message = "transient then success")
            producer.send(ProducerRecord(topic, event.bookId, Json.encodeToString(event))).get()
            producer.flush()

            assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                while (deliveryService.delivered.isEmpty()) {
                    Thread.sleep(50)
                }
            }

            // Delivery succeeded exactly once, after one transient failure
            // (so the handler ran twice — attempt 1 threw, attempt 2 succeeded).
            deliveryService.delivered.size shouldBe 1
            deliveryService.delivered.first().bookId shouldBe "book-transient"
            deliveryService.attemptCount.get() shouldBe 2

            // The repository save runs inside the same retryable block, so it is
            // re-invoked on each attempt. The successful attempt persists exactly
            // one AlertEvent record for this bookId.
            val saved = repository.findRecent(10).filter { it.bookId == "book-transient" }
            saved.isEmpty() shouldBe false
            saved.first().message shouldBe "transient then success"

            // Sanity: nothing reached the DLQ — retries recovered the message.
            dlqWatcher.subscribe(listOf(dlqTopic))
            val polled = dlqWatcher.poll(Duration.ofSeconds(2))
            polled.count() shouldBe 0
        } finally {
            job.cancel()
            producer.close()
            dlqWatcher.close()
            dlqProducer.close()
        }
    }

    test("permanent delivery failures exhaust retries and forward original payload to <topic>.dlq") {
        val topic = "alerts.retry-test-${UUID.randomUUID()}"
        val dlqTopic = "$topic.dlq"
        KafkaTestSetup.ensureTopic(bootstrapServers, topic)
        KafkaTestSetup.ensureTopic(bootstrapServers, dlqTopic)

        val repository = InMemoryAlertEventRepository()
        val deliveryService = AlwaysFailingDeliveryService()

        val dlqProducer = KafkaTestSetup.createProducer(bootstrapServers)
        val retryableConsumer = RetryableConsumer(
            topic = topic,
            maxRetries = 2,
            baseDelayMs = 10,
            dlqProducer = dlqProducer,
        )
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "retry-permanent-${UUID.randomUUID()}")
        val consumer = LimitBreachEventConsumer(
            consumer = kafkaConsumer,
            deliveryService = deliveryService,
            eventRepository = repository,
            topic = topic,
            retryableConsumer = retryableConsumer,
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch { consumer.start() }

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val dlqWatcher = KafkaTestSetup.createConsumer(
            bootstrapServers,
            "retry-permanent-dlq-watcher-${UUID.randomUUID()}",
        )
        try {
            val event = buildEvent(bookId = "book-permanent", message = "always fails")
            val payload = Json.encodeToString(event)
            producer.send(ProducerRecord(topic, event.bookId, payload)).get()
            producer.flush()

            dlqWatcher.subscribe(listOf(dlqTopic))
            val dlqRecords = mutableListOf<Pair<String?, String?>>()

            assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                while (dlqRecords.isEmpty()) {
                    val polled = dlqWatcher.poll(Duration.ofMillis(500))
                    for (record in polled) {
                        dlqRecords.add(record.key() to record.value())
                    }
                }
            }

            // The original key/value (NOT a transformed payload) lands on the
            // DLQ exactly once — that's the contract of RetryableConsumer.
            dlqRecords.size shouldBe 1
            dlqRecords[0].first shouldBe "book-permanent"
            dlqRecords[0].second shouldBe payload

            // maxRetries=2 means three handler invocations (attempt 0, 1, 2) all
            // hit deliver() before exhaustion. Anything less would mean retries
            // were not actually exercised.
            deliveryService.attemptCount.get() shouldBe 3
        } finally {
            job.cancel()
            producer.close()
            dlqWatcher.close()
            dlqProducer.close()
        }
    }
})
