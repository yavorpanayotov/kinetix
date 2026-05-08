package com.kinetix.common.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun trade(
    tradeId: String = "t-1",
    bookId: String = "book-1",
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

class TradeTest : FunSpec({

    test("create Trade with valid fields") {
        val t = trade()
        t.tradeId shouldBe TradeId("t-1")
        t.bookId shouldBe BookId("book-1")
        t.instrumentId shouldBe InstrumentId("AAPL")
        t.assetClass shouldBe AssetClass.EQUITY
        t.side shouldBe Side.BUY
        t.quantity shouldBe BigDecimal("100")
        t.price shouldBe Money(BigDecimal("150.00"), USD)
    }

    test("Trade with zero quantity throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> { trade(quantity = BigDecimal.ZERO) }
    }

    test("Trade with negative quantity throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> { trade(quantity = BigDecimal("-10")) }
    }

    test("Trade with negative price throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            trade(price = Money(BigDecimal("-1.00"), USD))
        }
    }

    test("Trade with zero price is allowed") {
        val t = trade(price = Money(BigDecimal.ZERO, USD))
        t.price.amount shouldBe BigDecimal.ZERO
    }

    test("notional value is quantity times price") {
        val t = trade(quantity = BigDecimal("100"), price = Money(BigDecimal("150.00"), USD))
        t.notional shouldBe Money(BigDecimal("15000.00"), USD)
    }

    test("BUY trade has positive signedQuantity") {
        val t = trade(side = Side.BUY, quantity = BigDecimal("100"))
        t.signedQuantity shouldBe BigDecimal("100")
    }

    test("SELL trade has negative signedQuantity") {
        val t = trade(side = Side.SELL, quantity = BigDecimal("100"))
        t.signedQuantity shouldBe BigDecimal("-100")
    }

    test("equal Trades are equal") {
        trade() shouldBe trade()
    }

    test("Trades with different tradeId are not equal") {
        trade(tradeId = "t-1") shouldNotBe trade(tradeId = "t-2")
    }
})
