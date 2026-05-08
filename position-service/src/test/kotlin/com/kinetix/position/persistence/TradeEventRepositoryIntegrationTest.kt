package com.kinetix.position.persistence

import com.kinetix.common.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
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

class TradeEventRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: TradeEventRepository = ExposedTradeEventRepository()

    beforeEach {
        newSuspendedTransaction { exec("TRUNCATE TABLE trade_events RESTART IDENTITY CASCADE") }
    }

    test("save and retrieve trade event by tradeId") {
        val t = trade()
        repository.save(t)

        val found = repository.findByTradeId(TradeId("t-1"))
        found.shouldNotBeNull()
        found.tradeId shouldBe TradeId("t-1")
        found.bookId shouldBe BookId("port-1")
        found.instrumentId shouldBe InstrumentId("AAPL")
        found.assetClass shouldBe AssetClass.EQUITY
        found.side shouldBe Side.BUY
        found.quantity.compareTo(BigDecimal("100")) shouldBe 0
        found.price.amount.compareTo(BigDecimal("150.00")) shouldBe 0
        found.price.currency shouldBe USD
        found.tradedAt shouldBe Instant.parse("2025-01-15T10:00:00Z")
    }

    test("findByTradeId returns null for non-existent trade") {
        repository.findByTradeId(TradeId("non-existent")).shouldBeNull()
    }

    test("findByBookId returns all trades for portfolio") {
        repository.save(trade(tradeId = "t-1", bookId = "port-1", instrumentId = "AAPL"))
        repository.save(trade(tradeId = "t-2", bookId = "port-1", instrumentId = "MSFT"))
        repository.save(trade(tradeId = "t-3", bookId = "port-2", instrumentId = "AAPL"))

        val results = repository.findByBookId(BookId("port-1"))
        results shouldHaveSize 2
        results.map { it.tradeId.value } shouldContainExactlyInAnyOrder listOf("t-1", "t-2")
    }

    test("findByBookId returns empty list for unknown portfolio") {
        repository.findByBookId(BookId("unknown")) shouldHaveSize 0
    }

    test("save trade with SELL side") {
        repository.save(trade(tradeId = "sell-1", side = Side.SELL))
        val found = repository.findByTradeId(TradeId("sell-1"))
        found.shouldNotBeNull()
        found.side shouldBe Side.SELL
    }

    test("save trade preserves BigDecimal precision") {
        val t = trade(
            tradeId = "precise-1",
            quantity = BigDecimal("12345.678901234"),
            price = Money(BigDecimal("98765.432109876"), USD),
        )
        repository.save(t)
        val found = repository.findByTradeId(TradeId("precise-1"))
        found.shouldNotBeNull()
        found.quantity.compareTo(BigDecimal("12345.678901234")) shouldBe 0
        found.price.amount.compareTo(BigDecimal("98765.432109876")) shouldBe 0
    }

    test("save trade with all asset classes round-trips correctly") {
        AssetClass.entries.forEachIndexed { idx, ac ->
            repository.save(trade(tradeId = "t-ac-$idx", assetClass = ac))
            val found = repository.findByTradeId(TradeId("t-ac-$idx"))
            found.shouldNotBeNull()
            found.assetClass shouldBe ac
        }
    }
})
