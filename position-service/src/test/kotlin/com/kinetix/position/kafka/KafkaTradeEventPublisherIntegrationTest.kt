package com.kinetix.position.kafka

import com.kinetix.testsupport.kafka.KafkaTestSetup
import com.kinetix.common.kafka.events.TradeEventMessage
import com.kinetix.common.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun trade(
    tradeId: String = "t-1",
    bookId: String = "port-1",
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    side: Side = Side.BUY,
    quantity: BigDecimal = BigDecimal("100"),
    price: Money = Money(BigDecimal("150.00"), USD),
    tradedAt: Instant = Instant.parse("2025-01-15T10:00:00Z"),
) = Trade(
    tradeId = TradeId(tradeId),
    bookId = BookId(bookId),
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    side = side,
    quantity = quantity,
    price = price,
    tradedAt = tradedAt,
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class KafkaTradeEventPublisherIntegrationTest : FunSpec({

    val bootstrapServers = KafkaTestSetup.start()

    test("publishes trade event to Kafka and consumer receives it") {
        val topic = "trades.lifecycle.roundtrip-test"
        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val publisher = KafkaTradeEventPublisher(producer, topic)

        publisher.publish(TradeEvent(trade = trade()))

        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, "roundtrip-test-group")
        consumer.subscribe(listOf(topic))

        val records = consumer.poll(Duration.ofSeconds(10))
        records.count() shouldBe 1

        val record = records.first()
        record.key() shouldBe "port-1"

        val event = Json.decodeFromString<TradeEventMessage>(record.value())
        event.tradeId shouldBe "t-1"
        event.bookId shouldBe "port-1"
        event.instrumentId shouldBe "AAPL"
        event.assetClass shouldBe "EQUITY"
        event.side shouldBe "BUY"
        event.quantity shouldBe "100"
        event.priceAmount shouldBe "150.00"
        event.priceCurrency shouldBe "USD"
        event.tradedAt shouldContain "2025-01-15T10:00:00"

        consumer.close()
        producer.close()
    }

    test("uses bookId as partition key for ordering guarantee") {
        val topic = "trades.lifecycle.ordering-test"
        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        val publisher = KafkaTradeEventPublisher(producer, topic)

        publisher.publish(TradeEvent(trade = trade(tradeId = "t-ord-1", bookId = "port-A")))
        publisher.publish(TradeEvent(trade = trade(tradeId = "t-ord-2", bookId = "port-A")))

        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, "ordering-test-group")
        consumer.subscribe(listOf(topic))

        val records = consumer.poll(Duration.ofSeconds(10))
        records.count() shouldBe 2

        val partitions = records.map { it.partition() }.toSet()
        partitions.size shouldBe 1 // same book → same partition

        val events = records.map { Json.decodeFromString<TradeEventMessage>(it.value()) }
        events[0].tradeId shouldBe "t-ord-1"
        events[1].tradeId shouldBe "t-ord-2"

        consumer.close()
        producer.close()
    }
})
