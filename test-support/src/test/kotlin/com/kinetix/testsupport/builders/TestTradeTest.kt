package com.kinetix.testsupport.builders

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeEventType
import com.kinetix.common.model.TradeStatus
import com.kinetix.common.model.TraderId
import com.kinetix.common.model.instrument.InstrumentTypeCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Smoke test for [TestTrade]. Covers the defaults, the chainable `withX`
 * surface, and the immutability guarantee. The point is that tests
 * elsewhere in the platform can rely on `aTrade()` producing a valid
 * [com.kinetix.common.model.Trade] with stable, deterministic field values.
 */
class TestTradeTest : FunSpec({

    test("aTrade().build() returns a non-null Trade with sensible defaults") {
        val trade = TestTrade.aTrade().build()

        trade.shouldNotBeNull()
        trade.instrumentId shouldBe InstrumentId("AAPL")
        trade.quantity shouldBe BigDecimal(100)
        trade.side shouldBe Side.BUY
        trade.price shouldBe Money(BigDecimal("100.00"), Currency.getInstance("USD"))
        trade.tradedAt shouldBe Instant.parse("2026-01-01T00:00:00Z")
        trade.assetClass shouldBe AssetClass.EQUITY
        trade.instrumentType shouldBe InstrumentTypeCode.CASH_EQUITY
        trade.eventType shouldBe TradeEventType.NEW
        trade.status shouldBe TradeStatus.LIVE
    }

    test("withSymbol overrides the instrument identifier on the built Trade") {
        val trade = TestTrade.aTrade().withSymbol("MSFT").build()

        trade.instrumentId shouldBe InstrumentId("MSFT")
    }

    test("withQuantity accepts an Int and sets the trade quantity") {
        val trade = TestTrade.aTrade().withQuantity(250).build()

        trade.quantity shouldBe BigDecimal(250)
    }

    test("withQuantity accepts a BigDecimal and sets the trade quantity") {
        val trade = TestTrade.aTrade().withQuantity(BigDecimal("12.5")).build()

        trade.quantity shouldBe BigDecimal("12.5")
    }

    test("withSide overrides the trade side") {
        val trade = TestTrade.aTrade().withSide(Side.SELL).build()

        trade.side shouldBe Side.SELL
    }

    test("withPrice(amount, currency) overrides the price money") {
        val gbp = Currency.getInstance("GBP")
        val trade = TestTrade.aTrade().withPrice(BigDecimal("87.50"), gbp).build()

        trade.price shouldBe Money(BigDecimal("87.50"), gbp)
    }

    test("withBookId(String) wraps the value in a BookId") {
        val trade = TestTrade.aTrade().withBookId("book-eq-001").build()

        trade.bookId shouldBe BookId("book-eq-001")
    }

    test("withTraderId(null) clears the optional traderId") {
        val trade = TestTrade.aTrade().withTraderId(null as String?).build()

        trade.traderId shouldBe null
    }

    test("withTraderId(String) wraps the value in a TraderId") {
        val trade = TestTrade.aTrade().withTraderId("tr-eg-042").build()

        trade.traderId shouldBe TraderId("tr-eg-042")
    }

    test("two builders started from aTrade() are independent") {
        val first = TestTrade.aTrade().withSymbol("AAPL").withQuantity(100)
        val second = TestTrade.aTrade().withSymbol("MSFT").withQuantity(500)

        val firstTrade = first.build()
        val secondTrade = second.build()

        firstTrade.instrumentId shouldBe InstrumentId("AAPL")
        firstTrade.quantity shouldBe BigDecimal(100)
        secondTrade.instrumentId shouldBe InstrumentId("MSFT")
        secondTrade.quantity shouldBe BigDecimal(500)
        firstTrade shouldNotBe secondTrade
    }

    test("chained withX calls do not mutate the original builder") {
        val original = TestTrade.aTrade()
        val mutated = original.withSymbol("NFLX").withQuantity(7)

        original.build().instrumentId shouldBe InstrumentId("AAPL")
        original.build().quantity shouldBe BigDecimal(100)
        mutated.build().instrumentId shouldBe InstrumentId("NFLX")
        mutated.build().quantity shouldBe BigDecimal(7)
    }
})
