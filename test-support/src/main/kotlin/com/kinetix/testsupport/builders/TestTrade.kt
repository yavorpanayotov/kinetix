package com.kinetix.testsupport.builders

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.Trade
import com.kinetix.common.model.TradeEventType
import com.kinetix.common.model.TradeId
import com.kinetix.common.model.TradeStatus
import com.kinetix.common.model.TraderId
import com.kinetix.common.model.instrument.InstrumentTypeCode
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Chainable test fixture builder for [Trade]. Acceptance and unit tests that
 * need a `Trade` instance should reach for this builder rather than spelling
 * out every constructor argument, so tests stay focused on the behaviour
 * under test instead of irrelevant boilerplate.
 *
 * Sensible deterministic defaults — symbol `AAPL`, quantity 100, BUY side,
 * price `100.00 USD`, traded at `2026-01-01T00:00:00Z`, instrument type
 * `CASH_EQUITY` — keep tests reproducible. Override only the fields the
 * test actually cares about.
 *
 * Example:
 * ```
 * val trade = TestTrade.aTrade()
 *     .withSymbol("MSFT")
 *     .withQuantity(BigDecimal("250"))
 *     .withSide(Side.SELL)
 *     .build()
 * ```
 *
 * The builder is an immutable [data class] under the hood, so each `withX`
 * call returns a fresh instance and two builders started from [aTrade] are
 * independent.
 */
data class TestTrade(
    private val tradeId: TradeId = DEFAULT_TRADE_ID,
    private val bookId: BookId = DEFAULT_BOOK_ID,
    private val instrumentId: InstrumentId = DEFAULT_INSTRUMENT_ID,
    private val assetClass: AssetClass = AssetClass.EQUITY,
    private val side: Side = Side.BUY,
    private val quantity: BigDecimal = DEFAULT_QUANTITY,
    private val price: Money = DEFAULT_PRICE,
    private val tradedAt: Instant = DEFAULT_TRADED_AT,
    private val eventType: TradeEventType = TradeEventType.NEW,
    private val status: TradeStatus = TradeStatus.LIVE,
    private val originalTradeId: TradeId? = null,
    private val counterpartyId: String? = DEFAULT_COUNTERPARTY_ID,
    private val instrumentType: InstrumentTypeCode = InstrumentTypeCode.CASH_EQUITY,
    private val strategyId: String? = null,
    private val traderId: TraderId? = DEFAULT_TRADER_ID,
) {

    fun withTradeId(tradeId: TradeId): TestTrade = copy(tradeId = tradeId)

    fun withTradeId(tradeId: String): TestTrade = copy(tradeId = TradeId(tradeId))

    fun withSymbol(symbol: String): TestTrade = copy(instrumentId = InstrumentId(symbol))

    fun withInstrumentId(instrumentId: InstrumentId): TestTrade = copy(instrumentId = instrumentId)

    fun withBookId(bookId: BookId): TestTrade = copy(bookId = bookId)

    fun withBookId(bookId: String): TestTrade = copy(bookId = BookId(bookId))

    fun withAssetClass(assetClass: AssetClass): TestTrade = copy(assetClass = assetClass)

    fun withSide(side: Side): TestTrade = copy(side = side)

    fun withQuantity(quantity: BigDecimal): TestTrade = copy(quantity = quantity)

    fun withQuantity(quantity: Int): TestTrade = copy(quantity = BigDecimal(quantity))

    fun withQuantity(quantity: Long): TestTrade = copy(quantity = BigDecimal(quantity))

    fun withPrice(price: Money): TestTrade = copy(price = price)

    fun withPrice(amount: BigDecimal, currency: Currency = USD): TestTrade =
        copy(price = Money(amount, currency))

    fun withPrice(amount: Double, currency: Currency = USD): TestTrade =
        copy(price = Money(BigDecimal.valueOf(amount), currency))

    fun withCurrency(currency: Currency): TestTrade =
        copy(price = Money(price.amount, currency))

    fun withTradedAt(tradedAt: Instant): TestTrade = copy(tradedAt = tradedAt)

    fun withEventType(eventType: TradeEventType): TestTrade = copy(eventType = eventType)

    fun withStatus(status: TradeStatus): TestTrade = copy(status = status)

    fun withOriginalTradeId(originalTradeId: TradeId?): TestTrade =
        copy(originalTradeId = originalTradeId)

    fun withCounterpartyId(counterpartyId: String?): TestTrade =
        copy(counterpartyId = counterpartyId)

    fun withInstrumentType(instrumentType: InstrumentTypeCode): TestTrade =
        copy(instrumentType = instrumentType)

    fun withStrategyId(strategyId: String?): TestTrade = copy(strategyId = strategyId)

    fun withTraderId(traderId: TraderId?): TestTrade = copy(traderId = traderId)

    fun withTraderId(traderId: String?): TestTrade =
        copy(traderId = traderId?.let(::TraderId))

    fun build(): Trade = Trade(
        tradeId = tradeId,
        bookId = bookId,
        instrumentId = instrumentId,
        assetClass = assetClass,
        side = side,
        quantity = quantity,
        price = price,
        tradedAt = tradedAt,
        eventType = eventType,
        status = status,
        originalTradeId = originalTradeId,
        counterpartyId = counterpartyId,
        instrumentType = instrumentType,
        strategyId = strategyId,
        traderId = traderId,
    )

    companion object {
        /** USD is the default trade currency for fixtures. */
        val USD: Currency = Currency.getInstance("USD")

        private val DEFAULT_TRADE_ID = TradeId("tr-test-0000000000000001")
        private val DEFAULT_BOOK_ID = BookId("book-test-001")
        private val DEFAULT_INSTRUMENT_ID = InstrumentId("AAPL")
        private val DEFAULT_QUANTITY: BigDecimal = BigDecimal(100)
        private val DEFAULT_PRICE = Money(BigDecimal("100.00"), USD)
        private val DEFAULT_TRADED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
        private const val DEFAULT_COUNTERPARTY_ID = "cpty-test-001"
        private val DEFAULT_TRADER_ID = TraderId("tr-eg-001")

        /** Starts a fresh [TestTrade] builder seeded with sensible defaults. */
        fun aTrade(): TestTrade = TestTrade()
    }
}
