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

class AuditEventConsumerIntegrationTest : FunSpec({

    val repository = ExposedAuditEventRepository()

    beforeSpec {
        DatabaseTestSetup.startAndMigrate()
    }

    test("consumes trade event and persists audit record") {
        val bootstrapServers = KafkaTestSetup.start()
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "audit-test-1")
        val consumer = AuditEventConsumer(kafkaConsumer, repository)

        val job = launch { consumer.start() }

        val event = TradeEventMessage(
            tradeId = "t-1",
            bookId = "port-1",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "100",
            priceAmount = "150.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T10:00:00Z",
        )

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord("trades.lifecycle", "port-1", Json.encodeToString(event))).get()

        withTimeout(10_000) {
            while (repository.findAll().isEmpty()) {
                delay(100)
            }
        }

        val events = repository.findAll()
        events.size shouldBe 1
        events[0].tradeId shouldBe "t-1"
        events[0].bookId shouldBe "port-1"
        events[0].instrumentId shouldBe "AAPL"
        events[0].assetClass shouldBe "EQUITY"
        events[0].side shouldBe "BUY"
        events[0].quantity shouldBe "100"
        events[0].priceAmount shouldBe "150"
        events[0].priceCurrency shouldBe "USD"
        events[0].tradedAt shouldBe "2025-01-15T10:00:00Z"

        job.cancel()
        producer.close()
    }

    test("consumes multiple events and stores all in order") {
        val bootstrapServers = KafkaTestSetup.start()
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "audit-test-2")
        val consumer = AuditEventConsumer(kafkaConsumer, repository, topic = "trades.lifecycle.multi")

        val job = launch { consumer.start() }

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val events = listOf(
            TradeEventMessage(tradeId = "t-10", bookId = "port-2", instrumentId = "MSFT", assetClass = "EQUITY", side = "BUY", quantity = "50", priceAmount = "300.00", priceCurrency = "USD", tradedAt = "2025-01-15T10:00:00Z"),
            TradeEventMessage(tradeId = "t-11", bookId = "port-2", instrumentId = "GOOG", assetClass = "EQUITY", side = "SELL", quantity = "25", priceAmount = "180.00", priceCurrency = "USD", tradedAt = "2025-01-15T11:00:00Z"),
            TradeEventMessage(tradeId = "t-12", bookId = "port-2", instrumentId = "TSLA", assetClass = "EQUITY", side = "BUY", quantity = "10", priceAmount = "250.00", priceCurrency = "USD", tradedAt = "2025-01-15T12:00:00Z"),
        )

        for (event in events) {
            producer.send(ProducerRecord("trades.lifecycle.multi", "port-2", Json.encodeToString(event))).get()
        }

        withTimeout(10_000) {
            while (repository.findByBookId("port-2").size < 3) {
                delay(100)
            }
        }

        val stored = repository.findByBookId("port-2")
        stored.size shouldBe 3
        stored[0].tradeId shouldBe "t-10"
        stored[1].tradeId shouldBe "t-11"
        stored[2].tradeId shouldBe "t-12"

        job.cancel()
        producer.close()
    }
})
