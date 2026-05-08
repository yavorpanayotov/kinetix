package com.kinetix.position.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.Trade
import com.kinetix.common.model.TradeEventType
import com.kinetix.common.model.TradeId
import com.kinetix.common.model.TradeStatus
import com.kinetix.position.model.StrategyType
import com.kinetix.position.model.TradeStrategy
import com.kinetix.position.persistence.TradeStrategyRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

private val USD = Currency.getInstance("USD")
private val BOOK = BookId("book-strat-1")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)

private fun trade(
    tradeId: String,
    instrumentId: String = "AAPL",
    side: Side = Side.BUY,
    quantity: String = "100",
    price: String = "100.00",
    strategyId: String? = null,
) = Trade(
    tradeId = TradeId(tradeId),
    bookId = BOOK,
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.EQUITY,
    side = side,
    quantity = BigDecimal(quantity),
    price = usd(price),
    tradedAt = Instant.parse("2025-06-01T10:00:00Z"),
    eventType = TradeEventType.NEW,
    status = TradeStatus.LIVE,
    strategyId = strategyId,
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class TradeStrategyServiceTest : FunSpec({

    val strategyRepository = mockk<TradeStrategyRepository>()
    val service = TradeStrategyService(strategyRepository)

    test("creates a strategy and persists it with a generated id") {
        val captured = slot<TradeStrategy>()
        coEvery { strategyRepository.save(capture(captured)) } returns Unit

        service.createStrategy(
            bookId = BOOK,
            strategyType = StrategyType.STRADDLE,
            name = "Sep AAPL Straddle",
        )

        coVerify(exactly = 1) { strategyRepository.save(any()) }
        captured.captured.bookId shouldBe BOOK
        captured.captured.strategyType shouldBe StrategyType.STRADDLE
        captured.captured.name shouldBe "Sep AAPL Straddle"
        // strategyId is a valid UUID
        UUID.fromString(captured.captured.strategyId) // throws if not valid UUID
    }

    test("creates a strategy with no name") {
        val captured = slot<TradeStrategy>()
        coEvery { strategyRepository.save(capture(captured)) } returns Unit

        service.createStrategy(
            bookId = BOOK,
            strategyType = StrategyType.CUSTOM,
            name = null,
        )

        captured.captured.name shouldBe null
        captured.captured.strategyType shouldBe StrategyType.CUSTOM
    }

    test("lists strategies for a book by delegating to repository") {
        val strategies = listOf(
            TradeStrategy(
                strategyId = "s-1",
                bookId = BOOK,
                strategyType = StrategyType.STRADDLE,
                name = "My Straddle",
                createdAt = Instant.now(),
            ),
        )
        coEvery { strategyRepository.findByBookId(BOOK) } returns strategies

        val result = service.listStrategies(BOOK)

        result shouldBe strategies
    }

    test("aggregates net Greeks as sum of legs — delta nets to zero for a straddle") {
        val legs = listOf(
            trade("t-call", side = Side.BUY, quantity = "1"),
            trade("t-put", side = Side.BUY, quantity = "1"),
        )
        // Simulated per-leg Greeks: call delta=0.5, put delta=-0.5
        val callGreeks = LegGreeks(delta = BigDecimal("0.5"), gamma = BigDecimal("0.05"), vega = BigDecimal("10.0"), theta = BigDecimal("-5.0"))
        val putGreeks = LegGreeks(delta = BigDecimal("-0.5"), gamma = BigDecimal("0.05"), vega = BigDecimal("10.0"), theta = BigDecimal("-5.0"))

        val result = service.aggregateGreeks(listOf(callGreeks, putGreeks))

        result.deltaNet.compareTo(BigDecimal.ZERO) shouldBe 0
        result.gammaNet.compareTo(BigDecimal("0.10")) shouldBe 0
        result.vegaNet.compareTo(BigDecimal("20.0")) shouldBe 0
        result.thetaNet.compareTo(BigDecimal("-10.0")) shouldBe 0
    }

    test("aggregates net Greeks for a single leg") {
        val greeks = listOf(
            LegGreeks(delta = BigDecimal("1.0"), gamma = BigDecimal("0.02"), vega = BigDecimal("15.0"), theta = BigDecimal("-3.0")),
        )

        val result = service.aggregateGreeks(greeks)

        result.deltaNet.compareTo(BigDecimal("1.0")) shouldBe 0
        result.gammaNet.compareTo(BigDecimal("0.02")) shouldBe 0
        result.vegaNet.compareTo(BigDecimal("15.0")) shouldBe 0
        result.thetaNet.compareTo(BigDecimal("-3.0")) shouldBe 0
    }

    test("aggregates net Greeks for empty legs produces zeros") {
        val result = service.aggregateGreeks(emptyList())

        result.deltaNet.compareTo(BigDecimal.ZERO) shouldBe 0
        result.gammaNet.compareTo(BigDecimal.ZERO) shouldBe 0
        result.vegaNet.compareTo(BigDecimal.ZERO) shouldBe 0
        result.thetaNet.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("aggregates net P&L as sum of per-leg unrealized P&L") {
        val pnls = listOf(BigDecimal("250.00"), BigDecimal("-100.00"), BigDecimal("75.50"))

        val result = service.aggregatePnl(pnls)

        result.compareTo(BigDecimal("225.50")) shouldBe 0
    }

    test("aggregates net P&L for empty list returns zero") {
        val result = service.aggregatePnl(emptyList())

        result.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("findById delegates to repository") {
        val strategy = TradeStrategy(
            strategyId = "s-find-1",
            bookId = BOOK,
            strategyType = StrategyType.COLLAR,
            name = null,
            createdAt = Instant.now(),
        )
        coEvery { strategyRepository.findById("s-find-1") } returns strategy

        val result = service.findById("s-find-1")

        result shouldBe strategy
    }

    test("findById returns null when strategy does not exist") {
        coEvery { strategyRepository.findById("nonexistent") } returns null

        val result = service.findById("nonexistent")

        result shouldBe null
    }
})
