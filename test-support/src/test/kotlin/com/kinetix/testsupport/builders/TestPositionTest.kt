package com.kinetix.testsupport.builders

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.instrument.InstrumentTypeCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.util.Currency

/**
 * Smoke test for [TestPosition]. Covers the defaults, the chainable `withX`
 * surface, and the immutability guarantee. The point is that tests elsewhere
 * in the platform can rely on `aPosition()` producing a valid
 * [com.kinetix.common.model.Position] with stable, deterministic field values.
 */
class TestPositionTest : FunSpec({

    test("aPosition().build() returns a non-null Position with sensible defaults") {
        val position = TestPosition.aPosition().build()

        position.shouldNotBeNull()
        position.bookId shouldBe BookId("book-test-001")
        position.instrumentId shouldBe InstrumentId("AAPL")
        position.assetClass shouldBe AssetClass.EQUITY
        position.quantity shouldBe BigDecimal(100)
        position.averageCost shouldBe Money(BigDecimal("100.00"), Currency.getInstance("USD"))
        position.marketPrice shouldBe Money(BigDecimal("100.00"), Currency.getInstance("USD"))
        position.realizedPnl shouldBe Money(BigDecimal.ZERO, Currency.getInstance("USD"))
        position.instrumentType shouldBe InstrumentTypeCode.CASH_EQUITY
        position.strategyId shouldBe null
        position.strategyType shouldBe null
        position.strategyName shouldBe null
    }

    test("withSymbol overrides the instrument identifier on the built Position") {
        val position = TestPosition.aPosition().withSymbol("MSFT").build()

        position.instrumentId shouldBe InstrumentId("MSFT")
    }

    test("withQuantity accepts an Int and sets the position quantity") {
        val position = TestPosition.aPosition().withQuantity(250).build()

        position.quantity shouldBe BigDecimal(250)
    }

    test("withQuantity accepts a BigDecimal and sets the position quantity") {
        val position = TestPosition.aPosition().withQuantity(BigDecimal("12.5")).build()

        position.quantity shouldBe BigDecimal("12.5")
    }

    test("withBookId(String) wraps the value in a BookId") {
        val position = TestPosition.aPosition().withBookId("book-eq-001").build()

        position.bookId shouldBe BookId("book-eq-001")
    }

    test("withMarketPrice(BigDecimal) preserves the existing currency") {
        val position = TestPosition.aPosition().withMarketPrice(BigDecimal("420.50")).build()

        position.marketPrice shouldBe Money(BigDecimal("420.50"), Currency.getInstance("USD"))
    }

    test("withAverageCost(Double) preserves the existing currency") {
        val position = TestPosition.aPosition().withAverageCost(85.25).build()

        position.averageCost shouldBe Money(BigDecimal.valueOf(85.25), Currency.getInstance("USD"))
    }

    test("withRealizedPnl(BigDecimal) preserves the existing currency") {
        val position = TestPosition.aPosition().withRealizedPnl(BigDecimal("250.00")).build()

        position.realizedPnl shouldBe Money(BigDecimal("250.00"), Currency.getInstance("USD"))
    }

    test("withCurrency re-denominates all money fields together so Position stays valid") {
        val gbp = Currency.getInstance("GBP")
        val position = TestPosition.aPosition().withCurrency(gbp).build()

        position.averageCost.currency shouldBe gbp
        position.marketPrice.currency shouldBe gbp
        position.realizedPnl.currency shouldBe gbp
    }

    test("withInstrumentType overrides the instrument type") {
        val position = TestPosition.aPosition()
            .withInstrumentType(InstrumentTypeCode.EQUITY_OPTION)
            .build()

        position.instrumentType shouldBe InstrumentTypeCode.EQUITY_OPTION
    }

    test("withStrategyId sets the optional strategy identifier") {
        val position = TestPosition.aPosition().withStrategyId("strat-001").build()

        position.strategyId shouldBe "strat-001"
    }

    test("two builders started from aPosition() are independent") {
        val first = TestPosition.aPosition().withSymbol("AAPL").withQuantity(100)
        val second = TestPosition.aPosition().withSymbol("MSFT").withQuantity(500)

        val firstPosition = first.build()
        val secondPosition = second.build()

        firstPosition.instrumentId shouldBe InstrumentId("AAPL")
        firstPosition.quantity shouldBe BigDecimal(100)
        secondPosition.instrumentId shouldBe InstrumentId("MSFT")
        secondPosition.quantity shouldBe BigDecimal(500)
        firstPosition shouldNotBe secondPosition
    }

    test("chained withX calls do not mutate the original builder") {
        val original = TestPosition.aPosition()
        val mutated = original.withSymbol("NFLX").withQuantity(7)

        original.build().instrumentId shouldBe InstrumentId("AAPL")
        original.build().quantity shouldBe BigDecimal(100)
        mutated.build().instrumentId shouldBe InstrumentId("NFLX")
        mutated.build().quantity shouldBe BigDecimal(7)
    }
})
