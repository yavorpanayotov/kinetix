package com.kinetix.position.kafka

import com.kinetix.common.kafka.events.PriceEvent
import com.kinetix.common.model.*
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.PositionsTable
import com.kinetix.position.service.PriceUpdateService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.ProducerRecord
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val AAPL = InstrumentId("AAPL")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)

private fun position(
    bookId: String = "port-1",
    instrumentId: InstrumentId = AAPL,
    quantity: String = "100",
    averageCost: String = "150.00",
    marketPrice: String = "155.00",
) = Position(
    bookId = BookId(bookId),
    instrumentId = instrumentId,
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal(quantity),
    averageCost = usd(averageCost),
    marketPrice = usd(marketPrice),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class PriceConsumerIntegrationTest : FunSpec({

    val repository = ExposedPositionRepository()
    val priceUpdateService = PriceUpdateService(repository)

    beforeSpec {
        DatabaseTestSetup.startAndMigrate()
    }

    beforeEach {
        newSuspendedTransaction { PositionsTable.deleteAll() }
    }

    test("consumes price event and updates position market price") {
        repository.save(position(bookId = "port-1", marketPrice = "155.00"))

        val bootstrapServers = KafkaTestSetup.start()
        val topic = "price.updates.test-1"
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "price-test-1")
        val consumer = PriceConsumer(kafkaConsumer, priceUpdateService, topic)

        val job = launch { consumer.start() }

        val event = PriceEvent(
            instrumentId = "AAPL",
            priceAmount = "170.00",
            priceCurrency = "USD",
            timestamp = "2025-01-15T10:00:00Z",
            source = "BLOOMBERG",
        )

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord(topic, "AAPL", Json.encodeToString(event))).get()

        withTimeout(10_000) {
            while (true) {
                val pos = repository.findByKey(BookId("port-1"), AAPL)
                if (pos != null && pos.marketPrice.amount.compareTo(BigDecimal("170.00")) == 0) break
                delay(100)
            }
        }

        val updated = repository.findByKey(BookId("port-1"), AAPL)!!
        updated.marketPrice.amount.compareTo(BigDecimal("170.00")) shouldBe 0
        updated.marketPrice.currency shouldBe USD
        updated.averageCost.amount.compareTo(BigDecimal("150.00")) shouldBe 0
        updated.quantity.compareTo(BigDecimal("100")) shouldBe 0

        job.cancel()
        producer.close()
    }

    test("updates multiple positions across portfolios for same instrument") {
        repository.save(position(bookId = "port-1", marketPrice = "155.00"))
        repository.save(position(bookId = "port-2", marketPrice = "155.00"))

        val bootstrapServers = KafkaTestSetup.start()
        val topic = "price.updates.test-2"
        val kafkaConsumer = KafkaTestSetup.createConsumer(bootstrapServers, "price-test-2")
        val consumer = PriceConsumer(kafkaConsumer, priceUpdateService, topic)

        val job = launch { consumer.start() }

        val event = PriceEvent(
            instrumentId = "AAPL",
            priceAmount = "180.00",
            priceCurrency = "USD",
            timestamp = "2025-01-15T11:00:00Z",
            source = "REUTERS",
        )

        val producer = KafkaTestSetup.createProducer(bootstrapServers)
        producer.send(ProducerRecord(topic, "AAPL", Json.encodeToString(event))).get()

        withTimeout(10_000) {
            while (true) {
                val positions = repository.findByInstrumentId(AAPL)
                if (positions.size == 2 && positions.all { it.marketPrice.amount.compareTo(BigDecimal("180.00")) == 0 }) break
                delay(100)
            }
        }

        val positions = repository.findByInstrumentId(AAPL)
        positions.size shouldBe 2
        positions.forEach {
            it.marketPrice.amount.compareTo(BigDecimal("180.00")) shouldBe 0
            it.marketPrice.currency shouldBe USD
        }

        job.cancel()
        producer.close()
    }
})
