package com.kinetix.position

import com.kinetix.common.model.*
import com.kinetix.position.kafka.KafkaTestSetup
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.AmendTradeCommand
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.CancelTradeCommand
import com.kinetix.position.service.ExposedTransactionalRunner
import com.kinetix.position.service.TradeBookingService
import com.kinetix.position.service.TradeLifecycleService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val TRADED_AT = Instant.parse("2025-01-15T10:00:00Z")

class TradeLifecycleAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val tradeRepo = ExposedTradeEventRepository(db)
    val positionRepo = ExposedPositionRepository(db)
    val transactional = ExposedTransactionalRunner(db)

    val bootstrapServers = KafkaTestSetup.start()
    val producer = KafkaTestSetup.createProducer(bootstrapServers)

    fun publisherFor(topic: String) = KafkaTradeEventPublisher(producer, topic)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE trade_events, positions RESTART IDENTITY CASCADE")
        }
    }

    // Scenario 5: amend changes position
    test("a booked trade of 100 AAPL at \$150 — the trade is amended to 200 shares at \$160 — original is AMENDED, amend trade references original, and position is 200 shares at \$160 avg cost") {
        val publisher = publisherFor("trades.lifecycle.tl-amend-1")
        val booking = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)
        val lifecycle = TradeLifecycleService(tradeRepo, positionRepo, transactional, publisher)

        booking.handle(
            BookTradeCommand(
                tradeId = TradeId("t-amend-orig"),
                bookId = BookId("port-amend-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = Money(BigDecimal("150.00"), USD),
                tradedAt = TRADED_AT,
                instrumentType = "CASH_EQUITY",
            ),
        )
        lifecycle.handleAmend(
            AmendTradeCommand(
                originalTradeId = TradeId("t-amend-orig"),
                newTradeId = TradeId("t-amend-new"),
                bookId = BookId("port-amend-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("200"),
                price = Money(BigDecimal("160.00"), USD),
                tradedAt = TRADED_AT,
                instrumentType = "CASH_EQUITY",
            ),
        )

        val original = tradeRepo.findByTradeId(TradeId("t-amend-orig"))
        original.shouldNotBeNull()
        original.status shouldBe TradeStatus.AMENDED

        val amended = tradeRepo.findByTradeId(TradeId("t-amend-new"))
        amended.shouldNotBeNull()
        amended.eventType shouldBe TradeEventType.AMEND
        amended.originalTradeId shouldBe TradeId("t-amend-orig")

        val position = positionRepo.findByKey(BookId("port-amend-1"), InstrumentId("AAPL"))
        position.shouldNotBeNull()
        position.quantity.compareTo(BigDecimal("200")) shouldBe 0
        position.averageCost.amount.compareTo(BigDecimal("160.00")) shouldBe 0
    }

    // Scenario 6: cancel reverses position to zero
    test("a booked trade of 100 AAPL at \$150 (for cancel) — the trade is cancelled — trade is CANCELLED and position quantity is zero") {
        val publisher = publisherFor("trades.lifecycle.tl-cancel-1")
        val booking = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)
        val lifecycle = TradeLifecycleService(tradeRepo, positionRepo, transactional, publisher)

        booking.handle(
            BookTradeCommand(
                tradeId = TradeId("t-cancel-1"),
                bookId = BookId("port-cancel-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = Money(BigDecimal("150.00"), USD),
                tradedAt = TRADED_AT,
                instrumentType = "CASH_EQUITY",
            ),
        )
        lifecycle.handleCancel(
            CancelTradeCommand(TradeId("t-cancel-1"), BookId("port-cancel-1")),
        )

        val trade = tradeRepo.findByTradeId(TradeId("t-cancel-1"))
        trade.shouldNotBeNull()
        trade.status shouldBe TradeStatus.CANCELLED

        val position = positionRepo.findByKey(BookId("port-cancel-1"), InstrumentId("AAPL"))
        position.shouldNotBeNull()
        position.quantity.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    // Scenario 9: realized P&L through lifecycle
    test("a BUY position of 200 AAPL at \$100 average cost — 50 shares are SOLD at \$120 — realized P&L is \$1000 and remaining position is 150 shares") {
        val publisher = publisherFor("trades.lifecycle.tl-pnl-1")
        val booking = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)

        booking.handle(
            BookTradeCommand(
                tradeId = TradeId("t-pnl-buy"),
                bookId = BookId("port-pnl-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("200"),
                price = Money(BigDecimal("100.00"), USD),
                tradedAt = TRADED_AT,
                instrumentType = "CASH_EQUITY",
            ),
        )
        booking.handle(
            BookTradeCommand(
                tradeId = TradeId("t-pnl-sell"),
                bookId = BookId("port-pnl-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.SELL,
                quantity = BigDecimal("50"),
                price = Money(BigDecimal("120.00"), USD),
                tradedAt = TRADED_AT,
                instrumentType = "CASH_EQUITY",
            ),
        )

        val position = positionRepo.findByKey(BookId("port-pnl-1"), InstrumentId("AAPL"))
        position.shouldNotBeNull()
        position.realizedPnl.amount.compareTo(BigDecimal("1000.00")) shouldBe 0
        position.quantity.compareTo(BigDecimal("150")) shouldBe 0
    }

    afterSpec {
        producer.close()
    }
})
