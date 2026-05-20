package com.kinetix.audit

import com.kinetix.audit.kafka.AuditEventConsumer
import com.kinetix.audit.kafka.GovernanceAuditEventConsumer
import com.kinetix.audit.persistence.DatabaseTestSetup
import com.kinetix.audit.persistence.ExposedAuditEventRepository
import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.common.kafka.events.TradeEventMessage
import com.kinetix.testsupport.kafka.KafkaTestSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.ProducerRecord
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

/**
 * PR 2.4 — proves that an inbound event's `correlationId` survives the Kafka
 * consumer hop and lands on the stored, hash-chained `AuditEvent`. Exercises
 * both the trade-event consumer and the governance consumer over a real
 * Kafka broker and a real Postgres instance (Testcontainers).
 */
class CorrelationIdConsumptionAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedAuditEventRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE audit_events RESTART IDENTITY")
        }
    }

    test("a trade event carrying a correlationId is consumed — the stored audit record carries the same correlationId") {
        val bootstrapServers = KafkaTestSetup.start()
        val topic = "trades.lifecycle.corr-${UUID.randomUUID()}"
        val correlationId = "corr-trade-${UUID.randomUUID()}"

        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "audit-corr-trade")
        val consumer = AuditEventConsumer(kafkaConsumer, repository, topic = topic)
        val job = launch { consumer.start() }

        val event = TradeEventMessage(
            tradeId = "t-corr-1",
            bookId = "port-corr",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "100",
            priceAmount = "150.00",
            priceCurrency = "USD",
            tradedAt = "2026-05-20T10:00:00Z",
            correlationId = correlationId,
        )

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord(topic, "port-corr", Json.encodeToString(event))).get()

        withTimeout(20_000) {
            while (repository.findAll().isEmpty()) {
                delay(100)
            }
        }

        val stored = repository.findAll()
        stored.size shouldBe 1
        stored[0].tradeId shouldBe "t-corr-1"
        stored[0].correlationId shouldBe correlationId

        job.cancel()
        producer.close()
    }

    test("a governance audit event carrying a correlationId is consumed — the stored audit record carries the same correlationId") {
        val bootstrapServers = KafkaTestSetup.start()
        val topic = "governance.audit.corr-${UUID.randomUUID()}"
        val correlationId = "corr-gov-${UUID.randomUUID()}"

        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "audit-corr-gov")
        val consumer = GovernanceAuditEventConsumer(kafkaConsumer, repository, topic = topic)
        val job = launch { consumer.start() }

        val event = GovernanceAuditEvent(
            eventType = AuditEventType.MODEL_STATUS_CHANGED,
            userId = "risk-mgr-1",
            userRole = "RISK_MANAGER",
            modelName = "VaR-v2",
            details = "DRAFT->VALIDATED",
            correlationId = correlationId,
        )

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord(topic, "key", Json.encodeToString(event))).get()

        withTimeout(20_000) {
            while (repository.findAll().isEmpty()) {
                delay(100)
            }
        }

        val stored = repository.findAll()
        stored.size shouldBe 1
        stored[0].eventType shouldBe "MODEL_STATUS_CHANGED"
        stored[0].correlationId shouldBe correlationId

        job.cancel()
        producer.close()
    }

    test("a RISK_CALCULATION_FAILED governance event is consumed — the failure is persisted end-to-end with its details and correlationId preserved") {
        val bootstrapServers = KafkaTestSetup.start()
        val topic = "governance.audit.failed-${UUID.randomUUID()}"
        val correlationId = "corr-risk-failed-${UUID.randomUUID()}"
        val bookId = "book-risk-failed-${UUID.randomUUID()}"
        val errorDetails = "VaR calculation failed: risk-engine gRPC call timed out after 30s"

        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "audit-risk-failed")
        val consumer = GovernanceAuditEventConsumer(kafkaConsumer, repository, topic = topic)
        val job = launch { consumer.start() }

        val event = GovernanceAuditEvent(
            eventType = AuditEventType.RISK_CALCULATION_FAILED,
            userId = "system",
            userRole = "SYSTEM",
            bookId = bookId,
            details = errorDetails,
            correlationId = correlationId,
        )

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord(topic, bookId, Json.encodeToString(event))).get()

        withTimeout(20_000) {
            while (repository.findAll().isEmpty()) {
                delay(100)
            }
        }

        val stored = repository.findAll()
        stored.size shouldBe 1
        stored[0].eventType shouldBe "RISK_CALCULATION_FAILED"
        stored[0].bookId shouldBe bookId
        stored[0].details shouldBe errorDetails
        stored[0].correlationId shouldBe correlationId

        job.cancel()
        producer.close()
    }
})
