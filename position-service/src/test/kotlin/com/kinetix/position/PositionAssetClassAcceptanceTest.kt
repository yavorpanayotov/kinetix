package com.kinetix.position

import com.kinetix.common.kafka.events.TradeEventMessage
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.common.model.TraderId
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.ExposedTransactionalRunner
import com.kinetix.position.service.TradeBookingService
import com.kinetix.testsupport.kafka.KafkaTestSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val TRADED_AT = Instant.parse("2026-05-28T10:00:00Z")

/**
 * Acceptance test pinning the per-instrument asset-class taxonomy that
 * position-service persists onto a Position when a trade is booked
 * (kx-trader-review P0 #3).
 *
 * The trader-review walkthrough on the live demo observed Treasury
 * instruments (`UST-5Y`, `UST-10Y`) rendering as "Cash Equity" / "Equity"
 * on the Trades blotter and Risk → Position Risk Breakdown. Root cause:
 * the demo-orchestrator inherited the book-level `EQUITY` tag for every
 * instrument it booked, regardless of what the instrument actually was.
 *
 * The contract this test pins down is: when a `UST-10Y` trade arrives at
 * position-service tagged `assetClass=FIXED_INCOME` / `instrumentType=
 * GOVERNMENT_BOND`, the resulting Position must carry those same values
 * round-trip (not collapse into EQUITY / CASH_EQUITY anywhere along the
 * persistence path). Equity bookings are kept alongside to discriminate
 * the test from a trivial constant.
 *
 * Real Postgres + real Kafka via Testcontainers — no mocks, in line with
 * the CLAUDE.md acceptance-test rule.
 */
class PositionAssetClassAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val tradeRepo = ExposedTradeEventRepository(db)
    val positionRepo = ExposedPositionRepository(db)
    val transactional = ExposedTransactionalRunner(db)

    val bootstrapServers = KafkaTestSetup.start()
    val producer = KafkaTestSetup.createProducer(bootstrapServers)

    fun publisherFor(topic: String) = KafkaTradeEventPublisher(producer, topic)

    fun consumerFor(topic: String, group: String): KafkaConsumer<String, String> {
        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, group)
        consumer.subscribe(listOf(topic))
        return consumer
    }

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE trade_events, positions RESTART IDENTITY CASCADE")
        }
    }

    test("a UST-10Y trade is persisted as assetClass=FIXED_INCOME, instrumentType=GOVERNMENT_BOND on the resulting Position") {
        val topic = "trades.lifecycle.ust10y-fixedincome"
        val consumer = consumerFor(topic, "ust10y-fixedincome-group")
        try {
            val publisher = publisherFor(topic)
            val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)

            service.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-ust10y-1"),
                    bookId = BookId("book-rates-1"),
                    instrumentId = InstrumentId("UST-10Y"),
                    assetClass = AssetClass.FIXED_INCOME,
                    side = Side.BUY,
                    quantity = BigDecimal("10000"),
                    price = Money(BigDecimal("98.50"), USD),
                    tradedAt = TRADED_AT,
                    instrumentType = "GOVERNMENT_BOND",
                    traderId = TraderId("tr-rt-001"),
                ),
            )

            val position = positionRepo.findByKey(BookId("book-rates-1"), InstrumentId("UST-10Y"))
            position shouldNotBe null
            position!!.assetClass shouldBe AssetClass.FIXED_INCOME
            position.instrumentType shouldBe InstrumentTypeCode.GOVERNMENT_BOND

            // Round-trip on the trade event too — the published Kafka envelope must
            // carry the same taxonomy so downstream consumers (risk-orchestrator,
            // gateway projections) classify the position correctly.
            val records = consumer.poll(Duration.ofSeconds(10))
            records.count() shouldBe 1
            val event = Json.decodeFromString<TradeEventMessage>(records.first().value())
            event.assetClass shouldBe "FIXED_INCOME"
            event.instrumentType shouldBe "GOVERNMENT_BOND"
        } finally {
            consumer.close()
        }
    }

    test("a UST-5Y trade is persisted as assetClass=FIXED_INCOME, instrumentType=GOVERNMENT_BOND on the resulting Position") {
        val topic = "trades.lifecycle.ust5y-fixedincome"
        val consumer = consumerFor(topic, "ust5y-fixedincome-group")
        try {
            val publisher = publisherFor(topic)
            val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)

            service.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-ust5y-1"),
                    bookId = BookId("book-rates-1"),
                    instrumentId = InstrumentId("UST-5Y"),
                    assetClass = AssetClass.FIXED_INCOME,
                    side = Side.BUY,
                    quantity = BigDecimal("8000"),
                    price = Money(BigDecimal("98.70"), USD),
                    tradedAt = TRADED_AT,
                    instrumentType = "GOVERNMENT_BOND",
                    traderId = TraderId("tr-rt-001"),
                ),
            )

            val position = positionRepo.findByKey(BookId("book-rates-1"), InstrumentId("UST-5Y"))
            position shouldNotBe null
            position!!.assetClass shouldBe AssetClass.FIXED_INCOME
            position.instrumentType shouldBe InstrumentTypeCode.GOVERNMENT_BOND
        } finally {
            consumer.close()
        }
    }

    test("an AAPL trade is persisted as assetClass=EQUITY, instrumentType=CASH_EQUITY (regression guard so the test discriminates)") {
        val topic = "trades.lifecycle.aapl-equity"
        val consumer = consumerFor(topic, "aapl-equity-group")
        try {
            val publisher = publisherFor(topic)
            val service = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)

            service.handle(
                BookTradeCommand(
                    tradeId = TradeId("t-aapl-1"),
                    bookId = BookId("book-equity-1"),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = Money(BigDecimal("185.50"), USD),
                    tradedAt = TRADED_AT,
                    instrumentType = "CASH_EQUITY",
                    traderId = TraderId("tr-eg-001"),
                ),
            )

            val position = positionRepo.findByKey(BookId("book-equity-1"), InstrumentId("AAPL"))
            position shouldNotBe null
            position!!.assetClass shouldBe AssetClass.EQUITY
            position.instrumentType shouldBe InstrumentTypeCode.CASH_EQUITY
        } finally {
            consumer.close()
        }
    }

})
