package com.kinetix.notification.kafka

import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.common.kafka.events.LimitBreachEvent
import com.kinetix.notification.delivery.DeliveryService
import com.kinetix.notification.model.AlertEvent
import com.kinetix.notification.model.DeliveryChannel
import com.kinetix.notification.persistence.InMemoryAlertEventRepository
import com.kinetix.testsupport.kafka.KafkaTestSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Integration test for [LimitBreachEventConsumer] using a real Testcontainers
 * Kafka broker. Verifies:
 *   1. A valid LimitBreachEvent payload is persisted as an AlertEvent and the
 *      delivery service is invoked.
 *   2. A poisoned (malformed) payload followed by a valid payload still
 *      results in the valid one being processed — the consumer loop survives
 *      bad messages rather than dying.
 */
class LimitBreachEventConsumerIntegrationTest : FunSpec({

    val bootstrapServers by lazy { KafkaTestSetup.start() }

    class RecordingDeliveryService : DeliveryService {
        val delivered = CopyOnWriteArrayList<AlertEvent>()
        override val channel: DeliveryChannel = DeliveryChannel.IN_APP
        override suspend fun deliver(event: AlertEvent) {
            delivered.add(event)
        }
    }

    fun newConsumer(
        topic: String,
        groupId: String,
        repository: InMemoryAlertEventRepository,
        deliveryService: DeliveryService,
    ): LimitBreachEventConsumer {
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, groupId)
        // Override RetryableConsumer with short delays so that a poisoned
        // payload doesn't stall the test for ~7s of exponential backoff.
        val retryableConsumer = RetryableConsumer(
            topic = topic,
            maxRetries = 1,
            baseDelayMs = 10,
        )
        return LimitBreachEventConsumer(
            consumer = kafkaConsumer,
            deliveryService = deliveryService,
            eventRepository = repository,
            topic = topic,
            retryableConsumer = retryableConsumer,
        )
    }

    fun buildEvent(
        bookId: String = "book-1",
        limitType: String = "VAR_95",
        severity: String = "HARD",
        currentValue: String = "1200000",
        limitValue: String = "1000000",
        message: String = "VaR limit breached",
    ) = LimitBreachEvent(
        eventId = UUID.randomUUID().toString(),
        bookId = bookId,
        limitType = limitType,
        severity = severity,
        currentValue = currentValue,
        limitValue = limitValue,
        message = message,
        breachedAt = "2025-01-15T10:00:00Z",
        tradeId = "trade-1",
        correlationId = "corr-1",
    )

    test("persists an AlertEvent and invokes delivery for a valid LimitBreachEvent") {
        val topic = "limits.breaches.test-${UUID.randomUUID()}"
        KafkaTestSetup.ensureTopic(bootstrapServers, topic)

        val repository = InMemoryAlertEventRepository()
        val deliveryService = RecordingDeliveryService()
        val consumer = newConsumer(topic, "limit-breach-test-clean", repository, deliveryService)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch { consumer.start() }

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        try {
            val event = buildEvent(bookId = "book-1", message = "clean payload")
            producer.send(ProducerRecord(topic, event.bookId, Json.encodeToString(event))).get()
            producer.flush()

            assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                runBlocking {
                    while (repository.findRecent(10).isEmpty() || deliveryService.delivered.isEmpty()) {
                        kotlinx.coroutines.delay(100)
                    }
                }
            }

            val recent = repository.findRecent(10)
            recent shouldHaveAtLeastSize 1
            recent.first().bookId shouldBe "book-1"
            recent.first().message shouldBe "clean payload"

            deliveryService.delivered shouldHaveAtLeastSize 1
            deliveryService.delivered.first().bookId shouldBe "book-1"
        } finally {
            job.cancel()
            producer.close()
        }
    }

    test("consumer loop survives a poisoned payload and still processes a subsequent valid event") {
        val topic = "limits.breaches.test-${UUID.randomUUID()}"
        KafkaTestSetup.ensureTopic(bootstrapServers, topic)

        val repository = InMemoryAlertEventRepository()
        val deliveryService = RecordingDeliveryService()
        val consumer = newConsumer(topic, "limit-breach-test-poison", repository, deliveryService)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch { consumer.start() }

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        try {
            // Poisoned: missing required fields (e.g. eventId, severity, etc.).
            producer.send(ProducerRecord(topic, "book-poison", "{\"not\":\"a limit breach\"}")).get()
            // Valid: should be processed despite the prior bad payload.
            val good = buildEvent(bookId = "book-2", message = "recovered after poison")
            producer.send(ProducerRecord(topic, good.bookId, Json.encodeToString(good))).get()
            producer.flush()

            assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                runBlocking {
                    while (
                        repository.findRecent(10).none { it.bookId == "book-2" } ||
                        deliveryService.delivered.none { it.bookId == "book-2" }
                    ) {
                        kotlinx.coroutines.delay(100)
                    }
                }
            }

            val recent = repository.findRecent(10)
            recent.any { it.bookId == "book-2" && it.message == "recovered after poison" } shouldBe true
            deliveryService.delivered.any { it.bookId == "book-2" } shouldBe true
        } finally {
            job.cancel()
            producer.close()
        }
    }
})
