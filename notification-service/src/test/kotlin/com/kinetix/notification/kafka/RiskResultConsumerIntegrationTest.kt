package com.kinetix.notification.kafka

import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.notification.delivery.DeliveryRouter
import com.kinetix.notification.delivery.DeliveryService
import com.kinetix.notification.engine.RulesEngine
import com.kinetix.notification.model.AlertEvent
import com.kinetix.notification.model.AlertRule
import com.kinetix.notification.model.AlertType
import com.kinetix.notification.model.ComparisonOperator
import com.kinetix.notification.model.DeliveryChannel
import com.kinetix.notification.model.Severity
import com.kinetix.notification.persistence.InMemoryAlertRuleRepository
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
 * Integration test for [RiskResultConsumer] using a real Testcontainers
 * Kafka broker. Verifies:
 *   1. A valid RiskResultEvent payload flows through the rules engine and
 *      the resulting alert is dispatched to the delivery router (and from
 *      there to the matching delivery service for the rule's channel).
 *   2. A poisoned (malformed) payload followed by a valid payload still
 *      results in the valid one being processed — the consumer loop survives
 *      bad messages rather than dying.
 */
class RiskResultConsumerIntegrationTest : FunSpec({

    val bootstrapServers by lazy { KafkaTestSetup.start() }

    class RecordingDeliveryService(
        override val channel: DeliveryChannel = DeliveryChannel.IN_APP,
    ) : DeliveryService {
        val delivered = CopyOnWriteArrayList<AlertEvent>()
        override suspend fun deliver(event: AlertEvent) {
            delivered.add(event)
        }
    }

    suspend fun newRulesEngine(): RulesEngine {
        val engine = RulesEngine(InMemoryAlertRuleRepository())
        engine.addRule(
            AlertRule(
                id = "r1",
                name = "VaR Limit",
                type = AlertType.VAR_BREACH,
                threshold = 100_000.0,
                operator = ComparisonOperator.GREATER_THAN,
                severity = Severity.CRITICAL,
                channels = listOf(DeliveryChannel.IN_APP),
            ),
        )
        return engine
    }

    fun newConsumer(
        topic: String,
        groupId: String,
        rulesEngine: RulesEngine,
        deliveryRouter: DeliveryRouter,
    ): RiskResultConsumer {
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, groupId)
        // Override RetryableConsumer with short delays so that a poisoned
        // payload doesn't stall the test for ~7s of exponential backoff.
        val retryableConsumer = RetryableConsumer(
            topic = topic,
            maxRetries = 1,
            baseDelayMs = 10,
        )
        return RiskResultConsumer(
            consumer = kafkaConsumer,
            rulesEngine = rulesEngine,
            deliveryRouter = deliveryRouter,
            topic = topic,
            retryableConsumer = retryableConsumer,
        )
    }

    fun buildEvent(
        bookId: String = "port-1",
        varValue: String = "150000.0",
        expectedShortfall: String = "180000.0",
        correlationId: String = "corr-${UUID.randomUUID()}",
    ) = RiskResultEvent(
        bookId = bookId,
        varValue = varValue,
        expectedShortfall = expectedShortfall,
        calculationType = "PARAMETRIC",
        calculatedAt = "2025-01-15T10:00:00Z",
        correlationId = correlationId,
    )

    test("evaluates rules and routes the resulting alert to the delivery service for a valid RiskResultEvent") {
        val topic = "risk.results.test-${UUID.randomUUID()}"
        KafkaTestSetup.ensureTopic(bootstrapServers, topic)

        val rulesEngine = newRulesEngine()
        val deliveryService = RecordingDeliveryService()
        val router = DeliveryRouter(listOf(deliveryService))
        val consumer = newConsumer(topic, "risk-result-test-clean", rulesEngine, router)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch { consumer.start() }

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        try {
            val event = buildEvent(bookId = "port-1", varValue = "150000.0")
            producer.send(ProducerRecord(topic, event.bookId, Json.encodeToString(event))).get()
            producer.flush()

            assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                runBlocking {
                    while (deliveryService.delivered.isEmpty()) {
                        kotlinx.coroutines.delay(100)
                    }
                }
            }

            deliveryService.delivered shouldHaveAtLeastSize 1
            val alert = deliveryService.delivered.first()
            alert.bookId shouldBe "port-1"
            alert.type shouldBe AlertType.VAR_BREACH
            alert.ruleId shouldBe "r1"
        } finally {
            job.cancel()
            producer.close()
        }
    }

    test("consumer loop survives a poisoned payload and still processes a subsequent valid event") {
        val topic = "risk.results.test-${UUID.randomUUID()}"
        KafkaTestSetup.ensureTopic(bootstrapServers, topic)

        val rulesEngine = newRulesEngine()
        val deliveryService = RecordingDeliveryService()
        val router = DeliveryRouter(listOf(deliveryService))
        val consumer = newConsumer(topic, "risk-result-test-poison", rulesEngine, router)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch { consumer.start() }

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        try {
            // Poisoned: missing required fields (e.g. bookId, varValue, etc.).
            producer.send(ProducerRecord(topic, "port-poison", "{\"not\":\"a risk result\"}")).get()
            // Valid: should be processed despite the prior bad payload.
            val good = buildEvent(bookId = "port-2", varValue = "200000.0")
            producer.send(ProducerRecord(topic, good.bookId, Json.encodeToString(good))).get()
            producer.flush()

            assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                runBlocking {
                    while (deliveryService.delivered.none { it.bookId == "port-2" }) {
                        kotlinx.coroutines.delay(100)
                    }
                }
            }

            deliveryService.delivered.any { it.bookId == "port-2" } shouldBe true
            deliveryService.delivered.first { it.bookId == "port-2" }.type shouldBe AlertType.VAR_BREACH
        } finally {
            job.cancel()
            producer.close()
        }
    }
})
