package com.kinetix.position.kafka

import com.kinetix.testsupport.kafka.KafkaTestSetup
import com.kinetix.position.fix.FIXSessionDisconnectedEvent
import com.kinetix.position.fix.KafkaFIXSessionEventPublisher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.time.Duration

class KafkaFIXSessionEventPublisherIntegrationTest : FunSpec({

    val bootstrapServers = KafkaTestSetup.start()

    test("publishes FIX_SESSION_DISCONNECTED event and consumer receives it with sessionId as key") {
        val topic = "fix.session.events.disconnect-test"
        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val publisher = KafkaFIXSessionEventPublisher(producer, topic)

        publisher.publishDisconnected(
            FIXSessionDisconnectedEvent(
                sessionId = "SES-001",
                counterparty = "Broker-A",
                occurredAt = "2025-01-15T10:05:00Z",
            )
        )

        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, "fix-disconnect-group")
        consumer.subscribe(listOf(topic))

        val records = consumer.poll(Duration.ofSeconds(10))
        records.count() shouldBe 1

        val record = records.first()
        record.key() shouldBe "SES-001"

        val event = Json.decodeFromString<FIXSessionDisconnectedEvent>(record.value())
        event.sessionId shouldBe "SES-001"
        event.counterparty shouldBe "Broker-A"
        event.occurredAt shouldBe "2025-01-15T10:05:00Z"

        consumer.close()
        producer.close()
    }

    test("uses sessionId as partition key to guarantee per-session ordering") {
        val topic = "fix.session.events.ordering-test"
        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val publisher = KafkaFIXSessionEventPublisher(producer, topic)

        publisher.publishDisconnected(
            FIXSessionDisconnectedEvent(
                sessionId = "SES-042",
                counterparty = "Broker-B",
                occurredAt = "2025-01-15T10:00:00Z",
            )
        )
        publisher.publishDisconnected(
            FIXSessionDisconnectedEvent(
                sessionId = "SES-042",
                counterparty = "Broker-B",
                occurredAt = "2025-01-15T10:00:30Z",
            )
        )

        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, "fix-ordering-group")
        consumer.subscribe(listOf(topic))

        val records = consumer.poll(Duration.ofSeconds(10))
        records.count() shouldBe 2

        val partitions = records.map { it.partition() }.toSet()
        partitions.size shouldBe 1

        val events = records.map { Json.decodeFromString<FIXSessionDisconnectedEvent>(it.value()) }
        events[0].occurredAt shouldBe "2025-01-15T10:00:00Z"
        events[1].occurredAt shouldBe "2025-01-15T10:00:30Z"

        consumer.close()
        producer.close()
    }
})
