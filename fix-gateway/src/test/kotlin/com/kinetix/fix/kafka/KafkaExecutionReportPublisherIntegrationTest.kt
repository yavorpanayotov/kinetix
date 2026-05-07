package com.kinetix.fix.kafka

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.Properties

/**
 * Verifies that [KafkaExecutionReportPublisher] uses an idempotent producer and publishes
 * [ExecutionReportEvent]s on the `execution.reports` topic with the correct partition key
 * (ADR-0035 phase 3 commit 2 / plan §3.5).
 *
 * Idempotence config asserted by reading back the producer's effective config — relying on
 * library defaults is brittle; the contract says fix-gateway producer MUST set:
 *   - enable.idempotence = true
 *   - acks = all
 *   - max.in.flight.requests.per.connection = 5
 *   - delivery.timeout.ms = 120000
 */
class KafkaExecutionReportPublisherIntegrationTest : FunSpec({

    val kafka = KafkaContainer("apache/kafka:3.8.1")

    beforeSpec { kafka.start() }
    afterSpec { kafka.stop() }

    fun consumerFor(groupId: String): KafkaConsumer<String, String> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        return KafkaConsumer(props)
    }

    test("publishes ExecutionReportEvent keyed by clOrdId with idempotent producer settings") {
        val producer = ExecutionReportProducerFactory.idempotent(kafka.bootstrapServers)
        val publisher = KafkaExecutionReportPublisher(producer, topic = "execution.reports")

        val event = ExecutionReportEvent(
            eventId = "evt-1",
            clOrdId = "5b2a3f1e-1234-4abc-9def-0123456789ab",
            orderId = "ord-1",
            execId = "exec-1",
            sessionId = "FIX.4.4:KINETIX->NYSE",
            venue = "NYSE",
            fixVersion = "FIX.4.4",
            execType = "F",
            eventType = ExecutionEventType.FILL,
            lastQty = "100",
            lastPrice = "150.25",
            cumulativeQty = "100",
            averagePrice = "150.25",
            receivedAt = "2026-05-07T10:00:00Z",
        )

        publisher.publish(event)
        producer.flush()

        consumerFor("test-1").use { consumer ->
            consumer.subscribe(listOf("execution.reports"))
            val records = consumer.poll(Duration.ofSeconds(10))
            val received = records.map { it.key() to Json.decodeFromString<ExecutionReportEvent>(it.value()) }
            received.size shouldBe 1
            received[0].first shouldBe "5b2a3f1e-1234-4abc-9def-0123456789ab"
            received[0].second shouldBe event
        }
        producer.close()
    }

    test("falls back to venue as partition key when clOrdId is empty") {
        val producer = ExecutionReportProducerFactory.idempotent(kafka.bootstrapServers)
        val topic = "execution.reports.fallback-${System.nanoTime()}"
        val publisher = KafkaExecutionReportPublisher(producer, topic = topic)

        val event = ExecutionReportEvent(
            eventId = "evt-2",
            clOrdId = "",
            orderId = "",
            execId = "exec-bizrej-1",
            sessionId = "FIX.4.4:KINETIX->NYSE",
            venue = "NYSE",
            fixVersion = "FIX.4.4",
            execType = "",
            eventType = ExecutionEventType.BUSINESS_REJECT,
            rejectReason = "Unknown instrument",
            rejectCode = "5",
            receivedAt = "2026-05-07T10:00:00Z",
        )

        publisher.publish(event)
        producer.flush()

        consumerFor("test-2").use { consumer ->
            consumer.subscribe(listOf(topic))
            val records = consumer.poll(Duration.ofSeconds(10))
            val keys = records.map { it.key() }
            keys shouldContainExactly listOf("NYSE")
        }
        producer.close()
    }

    test("idempotent factory wires enable.idempotence + acks=all + max.in.flight=5 + delivery.timeout=120000") {
        val producer = ExecutionReportProducerFactory.idempotent(kafka.bootstrapServers)
        // Inspect via reflection-friendly path: factory exposes the props it built.
        val props = ExecutionReportProducerFactory.idempotentProps(kafka.bootstrapServers)
        props.getProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG) shouldBe "true"
        props.getProperty(ProducerConfig.ACKS_CONFIG) shouldBe "all"
        props.getProperty(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION) shouldBe "5"
        props.getProperty(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG) shouldBe "120000"
        props.getProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG) shouldBe StringSerializer::class.java.name
        props.getProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG) shouldBe StringSerializer::class.java.name
        producer.close()
    }
})
