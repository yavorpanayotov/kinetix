package com.kinetix.position.kafka

import com.kinetix.testsupport.kafka.KafkaTestSetup
import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.position.fix.PrimeBrokerReconciliation
import com.kinetix.position.fix.ReconciliationBreak
import com.kinetix.position.fix.ReconciliationBreakSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

private fun reconciliation(
    bookId: String = "EQ-001",
    breaks: List<ReconciliationBreak>,
) = PrimeBrokerReconciliation(
    reconciliationDate = "2025-01-15",
    bookId = bookId,
    status = "COMPLETED",
    totalPositions = 10,
    matchedCount = 10 - breaks.size,
    breakCount = breaks.size,
    breaks = breaks,
    reconciledAt = Instant.parse("2025-01-15T17:00:00Z"),
)

private fun breakRow(
    instrumentId: String = "AAPL",
    severity: ReconciliationBreakSeverity = ReconciliationBreakSeverity.CRITICAL,
) = ReconciliationBreak(
    instrumentId = instrumentId,
    internalQty = BigDecimal("1000"),
    primeBrokerQty = BigDecimal("950"),
    breakQty = BigDecimal("50"),
    breakNotional = BigDecimal("15000.00"),
    severity = severity,
)

class KafkaReconciliationAlertPublisherIntegrationTest : FunSpec({

    val bootstrapServers = KafkaTestSetup.start()

    test("publishes one RECONCILIATION_BREAK event per call (per-break delivery, execution.allium:437-448)") {
        val topic = "risk.results.reconciliation-per-break-test"
        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val publisher = KafkaReconciliationAlertPublisher(producer, topic)

        val recon = reconciliation(
            breaks = listOf(
                breakRow(instrumentId = "AAPL"),
                breakRow(instrumentId = "MSFT"),
            )
        )
        // Service is responsible for the per-break threshold filter; the publisher
        // simply emits whatever break it is given.
        publisher.publishBreakAlert(recon, recon.breaks[0])
        publisher.publishBreakAlert(recon, recon.breaks[1])

        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, "reconciliation-per-break-group")
        consumer.subscribe(listOf(topic))

        val records = consumer.poll(Duration.ofSeconds(10))
        records.count() shouldBe 2

        val keys = records.map { it.key() }.toSet()
        keys shouldBe setOf("EQ-001|AAPL", "EQ-001|MSFT")

        records.forEach { record ->
            val event = Json.decodeFromString<RiskResultEvent>(record.value())
            event.bookId shouldBe "EQ-001"
            event.calculationType shouldBe "RECONCILIATION_BREAK"
        }

        consumer.close()
        producer.close()
    }

    test("alerts for the same (book, instrument) land on the same partition for ordering") {
        val topic = "risk.results.reconciliation-ordering-test"
        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val publisher = KafkaReconciliationAlertPublisher(producer, topic)

        val br = breakRow(instrumentId = "GBPUSD")
        publisher.publishBreakAlert(reconciliation(bookId = "FX-001", breaks = listOf(br)), br)
        publisher.publishBreakAlert(reconciliation(bookId = "FX-001", breaks = listOf(br)), br)

        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, "reconciliation-ordering-group")
        consumer.subscribe(listOf(topic))

        val records = consumer.poll(Duration.ofSeconds(10))
        records.count() shouldBe 2

        val partitions = records.map { it.partition() }.toSet()
        partitions.size shouldBe 1

        consumer.close()
        producer.close()
    }
})
