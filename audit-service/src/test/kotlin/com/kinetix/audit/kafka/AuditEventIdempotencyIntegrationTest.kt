package com.kinetix.audit.kafka

import com.kinetix.audit.persistence.DatabaseTestSetup
import com.kinetix.audit.persistence.ExposedAuditEventRepository
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
 * Kafka delivers at-least-once: a broker rebalance, a consumer crash before
 * offset commit, or a producer retry can all replay an already-processed
 * record. The audit trail must be exactly-once — a redelivered trade event
 * (identical topic/partition/offset) must not create a second persisted row.
 */
class AuditEventIdempotencyIntegrationTest : FunSpec({

    val repository = ExposedAuditEventRepository()

    beforeSpec {
        DatabaseTestSetup.startAndMigrate()
    }

    beforeEach {
        newSuspendedTransaction {
            exec("TRUNCATE TABLE audit_events RESTART IDENTITY")
        }
    }

    test("duplicate delivery of the same trade event persists only one audit record") {
        val bootstrapServers = KafkaTestSetup.start()
        val topic = "trades.lifecycle.idem-${UUID.randomUUID()}"

        val event = TradeEventMessage(
            tradeId = "t-dup-1",
            bookId = "port-dup",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "100",
            priceAmount = "150.00",
            priceCurrency = "USD",
            tradedAt = "2026-01-15T10:00:00Z",
        )
        val payload = Json.encodeToString(event)

        // Produce the SAME record once. It lands at a single (topic, partition, offset).
        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord(topic, "port-dup", payload)).get()
        producer.close()

        // First consumer pass: reads the record from offset 0 and persists it.
        val consumer1 = KafkaTestSetup.createConsumer(bootstrapServers, "audit-idem-grp-1")
        val auditConsumer1 = AuditEventConsumer(consumer1, repository, topic = topic)
        val job1 = launch { auditConsumer1.start() }
        withTimeout(15_000) {
            while (repository.findByTradeId("t-dup-1") == null) {
                delay(100)
            }
        }
        job1.cancel()

        // Second consumer pass: a fresh consumer group resets to earliest and
        // re-delivers the very same record — same topic/partition/offset.
        // This is exactly what at-least-once redelivery looks like.
        val consumer2 = KafkaTestSetup.createConsumer(bootstrapServers, "audit-idem-grp-2")
        val auditConsumer2 = AuditEventConsumer(consumer2, repository, topic = topic)
        val job2 = launch { auditConsumer2.start() }

        // Give the second pass time to consume and attempt the (idempotent) write.
        withTimeout(15_000) {
            var observed = 0
            while (observed < 5) {
                delay(200)
                observed++
            }
        }
        job2.cancel()

        // Exactly one audit row for this trade — the redelivery was deduped.
        val rows = repository.findAll().filter { it.tradeId == "t-dup-1" }
        rows.size shouldBe 1
        repository.countAll() shouldBe 1L
    }
})
