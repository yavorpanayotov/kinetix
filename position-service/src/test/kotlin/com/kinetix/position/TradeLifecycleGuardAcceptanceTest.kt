package com.kinetix.position

import com.kinetix.common.model.*
import com.kinetix.testsupport.kafka.KafkaTestSetup
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

class TradeLifecycleGuardAcceptanceTest : FunSpec({

    val usd = Currency.getInstance("USD")
    val tradedAt = Instant.parse("2025-01-15T10:00:00Z")
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

    test("cancelling an already-cancelled trade returns existing result idempotently") {
        val publisher = publisherFor("trades.lifecycle.tlg-cancel-twice")
        val booking = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)
        val lifecycle = TradeLifecycleService(tradeRepo, positionRepo, transactional, publisher)

        booking.handle(
            BookTradeCommand(
                tradeId = TradeId("t-cancel-twice"),
                bookId = BookId("port-cancel-2"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = Money(BigDecimal("150.00"), usd),
                tradedAt = tradedAt,
                instrumentType = "CASH_EQUITY",
                traderId = TraderId("tr-test-001"),
            ),
        )
        val firstResult = lifecycle.handleCancel(
            CancelTradeCommand(TradeId("t-cancel-twice"), BookId("port-cancel-2")),
        )

        val secondResult = lifecycle.handleCancel(
            CancelTradeCommand(TradeId("t-cancel-twice"), BookId("port-cancel-2")),
        )

        secondResult.trade.status shouldBe TradeStatus.CANCELLED
        secondResult.position.quantity.compareTo(firstResult.position.quantity) shouldBe 0
    }

    test("rejects amend on cancelled trade with InvalidTradeStateException") {
        val publisher = publisherFor("trades.lifecycle.tlg-amend-cancelled")
        val booking = TradeBookingService(tradeRepo, positionRepo, transactional, publisher)
        val lifecycle = TradeLifecycleService(tradeRepo, positionRepo, transactional, publisher)

        booking.handle(
            BookTradeCommand(
                tradeId = TradeId("t-amend-cancelled"),
                bookId = BookId("port-amend-cancel-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = Money(BigDecimal("150.00"), usd),
                tradedAt = tradedAt,
                instrumentType = "CASH_EQUITY",
                traderId = TraderId("tr-test-001"),
            ),
        )
        lifecycle.handleCancel(
            CancelTradeCommand(TradeId("t-amend-cancelled"), BookId("port-amend-cancel-1")),
        )

        val ex = shouldThrow<InvalidTradeStateException> {
            lifecycle.handleAmend(
                AmendTradeCommand(
                    originalTradeId = TradeId("t-amend-cancelled"),
                    newTradeId = TradeId("t-amend-cancelled-new"),
                    bookId = BookId("port-amend-cancel-1"),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("200"),
                    price = Money(BigDecimal("160.00"), usd),
                    tradedAt = tradedAt,
                    instrumentType = "CASH_EQUITY",
                ),
            )
        }
        ex.currentStatus shouldBe TradeStatus.CANCELLED
        ex.attemptedAction shouldBe "amend"
    }

    test("cancelling a non-existent trade throws TradeNotFoundException") {
        val publisher = publisherFor("trades.lifecycle.tlg-cancel-missing")
        val lifecycle = TradeLifecycleService(tradeRepo, positionRepo, transactional, publisher)

        val ex = shouldThrow<TradeNotFoundException> {
            lifecycle.handleCancel(
                CancelTradeCommand(TradeId("does-not-exist"), BookId("any-book")),
            )
        }
        ex.tradeId shouldBe "does-not-exist"
    }

    test("amending a non-existent trade throws TradeNotFoundException") {
        val publisher = publisherFor("trades.lifecycle.tlg-amend-missing")
        val lifecycle = TradeLifecycleService(tradeRepo, positionRepo, transactional, publisher)

        val ex = shouldThrow<TradeNotFoundException> {
            lifecycle.handleAmend(
                AmendTradeCommand(
                    originalTradeId = TradeId("does-not-exist"),
                    newTradeId = TradeId("does-not-exist-amend"),
                    bookId = BookId("any-book"),
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    side = Side.BUY,
                    quantity = BigDecimal("100"),
                    price = Money(BigDecimal("150.00"), usd),
                    tradedAt = tradedAt,
                    instrumentType = "CASH_EQUITY",
                ),
            )
        }
        ex.tradeId shouldBe "does-not-exist"
    }

    afterSpec {
        producer.close()
    }
})
