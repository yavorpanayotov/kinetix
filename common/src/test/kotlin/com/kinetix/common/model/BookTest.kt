package com.kinetix.common.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val BOOK_ID = BookId("book-1")
private val AAPL = InstrumentId("AAPL")
private val MSFT = InstrumentId("MSFT")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)

private fun trade(
    instrumentId: InstrumentId = AAPL,
    side: Side = Side.BUY,
    quantity: String = "100",
    price: String = "150.00",
    bookId: BookId = BOOK_ID,
) = Trade(
    tradeId = TradeId("t-${System.nanoTime()}"),
    bookId = bookId,
    instrumentId = instrumentId,
    assetClass = AssetClass.EQUITY,
    side = side,
    quantity = BigDecimal(quantity),
    price = usd(price),
    tradedAt = Instant.now(),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class BookTest : FunSpec({

    test("create Book with valid fields") {
        val b = Book(id = BOOK_ID, name = "Tech Book")
        b.id shouldBe BOOK_ID
        b.name shouldBe "Tech Book"
    }

    test("Book with blank name throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> { Book(id = BOOK_ID, name = "") }
        shouldThrow<IllegalArgumentException> { Book(id = BOOK_ID, name = "   ") }
    }

    test("new Book has empty positions") {
        val b = Book(id = BOOK_ID, name = "Empty")
        b.positions.shouldBeEmpty()
    }

    // Book trade

    test("book first trade creates new position") {
        val b = Book(id = BOOK_ID, name = "Tech")
            .bookTrade(trade(instrumentId = AAPL, side = Side.BUY, quantity = "100", price = "150.00"))

        b.positions shouldHaveSize 1
        val pos = b.positions[AAPL]!!
        pos.quantity shouldBe BigDecimal("100")
        pos.averageCost shouldBe usd("150.00")
    }

    test("book second trade for same instrument updates position") {
        val b = Book(id = BOOK_ID, name = "Tech")
            .bookTrade(trade(instrumentId = AAPL, quantity = "100", price = "150.00"))
            .bookTrade(trade(instrumentId = AAPL, quantity = "50", price = "156.00"))

        b.positions shouldHaveSize 1
        b.positions[AAPL]!!.quantity shouldBe BigDecimal("150")
    }

    test("book trade for different instrument creates second position") {
        val b = Book(id = BOOK_ID, name = "Tech")
            .bookTrade(trade(instrumentId = AAPL, quantity = "100", price = "150.00"))
            .bookTrade(trade(instrumentId = MSFT, quantity = "50", price = "400.00"))

        b.positions shouldHaveSize 2
    }

    test("book trade with mismatched bookId throws IllegalArgumentException") {
        val b = Book(id = BOOK_ID, name = "Tech")
        shouldThrow<IllegalArgumentException> {
            b.bookTrade(trade(bookId = BookId("other")))
        }
    }

    // Total market value

    test("totalMarketValue sums all positions in given currency") {
        val b = Book(id = BOOK_ID, name = "Tech")
            .bookTrade(trade(instrumentId = AAPL, quantity = "100", price = "150.00"))
            .bookTrade(trade(instrumentId = MSFT, quantity = "50", price = "400.00"))
            .markToMarket(AAPL, usd("155.00"))
            .markToMarket(MSFT, usd("410.00"))

        // AAPL: 100 * 155 = 15500, MSFT: 50 * 410 = 20500
        b.totalMarketValue(USD) shouldBe usd("36000.00")
    }

    test("totalMarketValue of empty book is zero") {
        val b = Book(id = BOOK_ID, name = "Empty")
        b.totalMarketValue(USD) shouldBe Money.zero(USD)
    }

    // Total unrealized P&L

    test("totalUnrealizedPnl sums all position P&Ls in given currency") {
        val b = Book(id = BOOK_ID, name = "Tech")
            .bookTrade(trade(instrumentId = AAPL, quantity = "100", price = "150.00"))
            .bookTrade(trade(instrumentId = MSFT, quantity = "50", price = "400.00"))
            .markToMarket(AAPL, usd("155.00"))
            .markToMarket(MSFT, usd("390.00"))

        // AAPL: (155-150)*100 = 500, MSFT: (390-400)*50 = -500
        b.totalUnrealizedPnl(USD) shouldBe usd("0.00")
    }

    test("totalUnrealizedPnl of empty book is zero") {
        val b = Book(id = BOOK_ID, name = "Empty")
        b.totalUnrealizedPnl(USD) shouldBe Money.zero(USD)
    }

    // Mark-to-market

    test("markToMarket updates specific position") {
        val b = Book(id = BOOK_ID, name = "Tech")
            .bookTrade(trade(instrumentId = AAPL, quantity = "100", price = "150.00"))
            .markToMarket(AAPL, usd("160.00"))

        b.positions[AAPL]!!.marketPrice shouldBe usd("160.00")
    }

    test("markToMarket for unknown instrument returns same book") {
        val b = Book(id = BOOK_ID, name = "Tech")
        val same = b.markToMarket(AAPL, usd("160.00"))
        same shouldBe b
    }

    // Integration scenario

    test("full scenario: buy, buy more, sell partial, mark to market, check P&L") {
        val b = Book(id = BOOK_ID, name = "Tech")
            // Buy 100 AAPL @ 150
            .bookTrade(trade(instrumentId = AAPL, side = Side.BUY, quantity = "100", price = "150.00"))
            // Buy 50 more AAPL @ 156 => 150 shares, avgCost = (100*150 + 50*156)/150 = 152
            .bookTrade(trade(instrumentId = AAPL, side = Side.BUY, quantity = "50", price = "156.00"))
            // Sell 30 AAPL @ 160 => 120 shares, avgCost stays 152
            .bookTrade(trade(instrumentId = AAPL, side = Side.SELL, quantity = "30", price = "160.00"))
            // Market price moves to 165
            .markToMarket(AAPL, usd("165.00"))

        val pos = b.positions[AAPL]!!
        pos.quantity shouldBe BigDecimal("120")
        pos.averageCost.amount.toDouble() shouldBe 152.0
        pos.marketPrice shouldBe usd("165.00")
        // Unrealized P&L = (165 - 152) * 120 = 1560
        pos.unrealizedPnl shouldBe usd("1560.00")
        // Market value = 165 * 120 = 19800
        pos.marketValue shouldBe usd("19800.00")
    }
})
