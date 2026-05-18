package com.kinetix.testsupport.builders

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.instrument.InstrumentTypeCode
import java.math.BigDecimal
import java.util.Currency

/**
 * Chainable test fixture builder for [Position]. Acceptance and unit tests
 * that need a `Position` instance should reach for this builder rather than
 * spelling out every constructor argument, so tests stay focused on the
 * behaviour under test instead of irrelevant boilerplate.
 *
 * Sensible deterministic defaults — book `book-test-001`, symbol `AAPL`,
 * quantity 100, average cost and market price both `100.00 USD`, zero
 * realized P&L, instrument type `CASH_EQUITY` — keep tests reproducible.
 * Override only the fields the test actually cares about.
 *
 * Example:
 * ```
 * val position = TestPosition.aPosition()
 *     .withSymbol("MSFT")
 *     .withQuantity(BigDecimal("250"))
 *     .withMarketPrice(BigDecimal("420.50"))
 *     .build()
 * ```
 *
 * The builder is an immutable [data class] under the hood, so each `withX`
 * call returns a fresh instance and two builders started from [aPosition]
 * are independent.
 *
 * [Position] enforces that `averageCost`, `marketPrice`, and `realizedPnl`
 * share a currency. The currency-aware helpers ([withCurrency],
 * [withAverageCost], [withMarketPrice], [withRealizedPnl]) update all
 * money fields together so the builder never produces an invalid
 * [Position].
 */
data class TestPosition(
    private val bookId: BookId = DEFAULT_BOOK_ID,
    private val instrumentId: InstrumentId = DEFAULT_INSTRUMENT_ID,
    private val assetClass: AssetClass = AssetClass.EQUITY,
    private val quantity: BigDecimal = DEFAULT_QUANTITY,
    private val averageCost: Money = DEFAULT_AVERAGE_COST,
    private val marketPrice: Money = DEFAULT_MARKET_PRICE,
    private val realizedPnl: Money = DEFAULT_REALIZED_PNL,
    private val instrumentType: InstrumentTypeCode = InstrumentTypeCode.CASH_EQUITY,
    private val strategyId: String? = null,
    private val strategyType: String? = null,
    private val strategyName: String? = null,
) {

    fun withBookId(bookId: BookId): TestPosition = copy(bookId = bookId)

    fun withBookId(bookId: String): TestPosition = copy(bookId = BookId(bookId))

    fun withSymbol(symbol: String): TestPosition = copy(instrumentId = InstrumentId(symbol))

    fun withInstrumentId(instrumentId: InstrumentId): TestPosition = copy(instrumentId = instrumentId)

    fun withAssetClass(assetClass: AssetClass): TestPosition = copy(assetClass = assetClass)

    fun withQuantity(quantity: BigDecimal): TestPosition = copy(quantity = quantity)

    fun withQuantity(quantity: Int): TestPosition = copy(quantity = BigDecimal(quantity))

    fun withQuantity(quantity: Long): TestPosition = copy(quantity = BigDecimal(quantity))

    fun withAverageCost(averageCost: Money): TestPosition = copy(averageCost = averageCost)

    fun withAverageCost(amount: BigDecimal): TestPosition =
        copy(averageCost = Money(amount, marketPrice.currency))

    fun withAverageCost(amount: Double): TestPosition =
        copy(averageCost = Money(BigDecimal.valueOf(amount), marketPrice.currency))

    fun withMarketPrice(marketPrice: Money): TestPosition = copy(marketPrice = marketPrice)

    fun withMarketPrice(amount: BigDecimal): TestPosition =
        copy(marketPrice = Money(amount, marketPrice.currency))

    fun withMarketPrice(amount: Double): TestPosition =
        copy(marketPrice = Money(BigDecimal.valueOf(amount), marketPrice.currency))

    fun withRealizedPnl(realizedPnl: Money): TestPosition = copy(realizedPnl = realizedPnl)

    fun withRealizedPnl(amount: BigDecimal): TestPosition =
        copy(realizedPnl = Money(amount, marketPrice.currency))

    fun withRealizedPnl(amount: Double): TestPosition =
        copy(realizedPnl = Money(BigDecimal.valueOf(amount), marketPrice.currency))

    /**
     * Re-denominates [averageCost], [marketPrice], and [realizedPnl] into
     * [currency] in one shot so the builder never produces a Position with
     * mismatched money currencies.
     */
    fun withCurrency(currency: Currency): TestPosition = copy(
        averageCost = Money(averageCost.amount, currency),
        marketPrice = Money(marketPrice.amount, currency),
        realizedPnl = Money(realizedPnl.amount, currency),
    )

    fun withInstrumentType(instrumentType: InstrumentTypeCode): TestPosition =
        copy(instrumentType = instrumentType)

    fun withStrategyId(strategyId: String?): TestPosition = copy(strategyId = strategyId)

    fun withStrategyType(strategyType: String?): TestPosition = copy(strategyType = strategyType)

    fun withStrategyName(strategyName: String?): TestPosition = copy(strategyName = strategyName)

    fun build(): Position = Position(
        bookId = bookId,
        instrumentId = instrumentId,
        assetClass = assetClass,
        quantity = quantity,
        averageCost = averageCost,
        marketPrice = marketPrice,
        realizedPnl = realizedPnl,
        instrumentType = instrumentType,
        strategyId = strategyId,
        strategyType = strategyType,
        strategyName = strategyName,
    )

    companion object {
        /** USD is the default position currency for fixtures. */
        val USD: Currency = Currency.getInstance("USD")

        private val DEFAULT_BOOK_ID = BookId("book-test-001")
        private val DEFAULT_INSTRUMENT_ID = InstrumentId("AAPL")
        private val DEFAULT_QUANTITY: BigDecimal = BigDecimal(100)
        private val DEFAULT_AVERAGE_COST = Money(BigDecimal("100.00"), USD)
        private val DEFAULT_MARKET_PRICE = Money(BigDecimal("100.00"), USD)
        private val DEFAULT_REALIZED_PNL = Money(BigDecimal.ZERO, USD)

        /** Starts a fresh [TestPosition] builder seeded with sensible defaults. */
        fun aPosition(): TestPosition = TestPosition()
    }
}
