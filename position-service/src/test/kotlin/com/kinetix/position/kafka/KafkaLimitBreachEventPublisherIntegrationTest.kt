package com.kinetix.position.kafka

import com.kinetix.testsupport.kafka.KafkaTestSetup
import com.kinetix.common.kafka.events.LimitBreachEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.time.Duration

private fun breachEvent(
    eventId: String = "evt-1",
    bookId: String = "BOOK-001",
    limitType: String = "VAR",
    severity: String = "HARD",
    message: String = "VaR limit breached",
) = LimitBreachEvent(
    eventId = eventId,
    bookId = bookId,
    limitType = limitType,
    severity = severity,
    currentValue = "1500000",
    limitValue = "1000000",
    message = message,
    breachedAt = "2026-04-29T10:00:00Z",
    tradeId = "t-1",
    correlationId = "corr-1",
)

class KafkaLimitBreachEventPublisherIntegrationTest : FunSpec({

    val bootstrapServers = KafkaTestSetup.start()

    test("publishes a LimitBreachEvent payload to the configured topic") {
        val topic = "limits.breaches.publish-test"
        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val publisher = KafkaLimitBreachEventPublisher(producer, topic)

        publisher.publish(breachEvent(eventId = "evt-1", bookId = "BOOK-001"))

        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, "limit-breach-publish-group")
        consumer.subscribe(listOf(topic))

        val records = consumer.poll(Duration.ofSeconds(10))
        records.count() shouldBe 1

        val record = records.first()
        record.key() shouldBe "BOOK-001"

        val event = Json.decodeFromString<LimitBreachEvent>(record.value())
        event.eventId shouldBe "evt-1"
        event.bookId shouldBe "BOOK-001"
        event.limitType shouldBe "VAR"
        event.severity shouldBe "HARD"

        consumer.close()
        producer.close()
    }

    test("uses bookId as partition key so breaches for the same book land on the same partition") {
        val topic = "limits.breaches.ordering-test"
        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val publisher = KafkaLimitBreachEventPublisher(producer, topic)

        publisher.publish(breachEvent(eventId = "e-1", bookId = "FX-001", limitType = "VAR"))
        publisher.publish(breachEvent(eventId = "e-2", bookId = "FX-001", limitType = "NOTIONAL"))

        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, "limit-breach-ordering-group")
        consumer.subscribe(listOf(topic))

        val records = consumer.poll(Duration.ofSeconds(10))
        records.count() shouldBe 2

        val partitions = records.map { it.partition() }.toSet()
        partitions.size shouldBe 1

        consumer.close()
        producer.close()
    }

    test("publishes one event per breach when the booking trips multiple HARD limits") {
        val topic = "limits.breaches.multi-test"
        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val publisher = KafkaLimitBreachEventPublisher(producer, topic)

        publisher.publish(breachEvent(eventId = "var-breach", limitType = "VAR"))
        publisher.publish(breachEvent(eventId = "notional-breach", limitType = "NOTIONAL"))

        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, "limit-breach-multi-group")
        consumer.subscribe(listOf(topic))

        val records = consumer.poll(Duration.ofSeconds(10))
        records.count() shouldBe 2

        val limitTypes = records.map {
            Json.decodeFromString<LimitBreachEvent>(it.value()).limitType
        }.toSet()
        limitTypes shouldBe setOf("VAR", "NOTIONAL")

        consumer.close()
        producer.close()
    }
})
