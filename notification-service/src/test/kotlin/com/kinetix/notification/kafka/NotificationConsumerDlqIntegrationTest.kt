package com.kinetix.notification.kafka

import com.kinetix.common.kafka.RetryableConsumer
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
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Integration test for the notification-service consumer's dead-letter-queue
 * behaviour using a real Testcontainers Kafka broker.
 *
 * Verifies the next-hop guarantee that goes beyond the survival check in
 * [LimitBreachEventConsumerIntegrationTest]: when a payload is permanently
 * undecodable, after retries are exhausted the [RetryableConsumer] must
 * publish the original key/value to the configured DLQ topic ("<topic>.dlq").
 *
 * The test wires a [LimitBreachEventConsumer] with a [RetryableConsumer]
 * whose [RetryableConsumer.dlqProducer] points at the same broker, produces
 * a malformed JSON payload (so deserialization throws on every attempt),
 * and subscribes a separate [org.apache.kafka.clients.consumer.KafkaConsumer]
 * to the "<topic>.dlq" topic to assert the failing message lands there.
 */
class NotificationConsumerDlqIntegrationTest : FunSpec({

    val bootstrapServers by lazy { KafkaTestSetup.start() }

    class RecordingDeliveryService : DeliveryService {
        val delivered = CopyOnWriteArrayList<AlertEvent>()
        override val channel: DeliveryChannel = DeliveryChannel.IN_APP
        override suspend fun deliver(event: AlertEvent) {
            delivered.add(event)
        }
    }

    test("permanently failing payload is forwarded to <topic>.dlq after retries exhausted") {
        val topic = "alerts.dlq-test-${UUID.randomUUID()}"
        val dlqTopic = "$topic.dlq"
        KafkaTestSetup.ensureTopic(bootstrapServers, topic)
        KafkaTestSetup.ensureTopic(bootstrapServers, dlqTopic)

        val repository = InMemoryAlertEventRepository()
        val deliveryService = RecordingDeliveryService()

        val dlqProducer = KafkaTestSetup.createProducer(bootstrapServers)
        // maxRetries=1, baseDelayMs=10 so a poisoned payload moves to the DLQ
        // quickly rather than stalling on exponential backoff.
        val retryableConsumer = RetryableConsumer(
            topic = topic,
            maxRetries = 1,
            baseDelayMs = 10,
            dlqProducer = dlqProducer,
        )
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "notification-dlq-test")
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
        val dlqWatcher = KafkaTestSetup.createConsumer(bootstrapServers, "notification-dlq-watcher-${UUID.randomUUID()}")
        try {
            // Permanently failing: not parseable as LimitBreachEvent. The handler
            // throws a SerializationException on every attempt, so after
            // maxRetries the RetryableConsumer should send key+value to the DLQ.
            val poisonKey = "book-poison"
            val poisonValue = "{\"not\":\"a limit breach\"}"
            producer.send(ProducerRecord(topic, poisonKey, poisonValue)).get()
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

            dlqRecords.size shouldBe 1
            dlqRecords[0].first shouldBe poisonKey
            dlqRecords[0].second shouldBe poisonValue

            // Sanity: the poisoned payload never produced a delivery or stored alert.
            deliveryService.delivered.isEmpty() shouldBe true
            repository.findRecent(10).isEmpty() shouldBe true
        } finally {
            job.cancel()
            producer.close()
            dlqWatcher.close()
            dlqProducer.close()
        }
    }
})
