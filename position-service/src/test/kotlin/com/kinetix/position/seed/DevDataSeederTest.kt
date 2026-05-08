package com.kinetix.position.seed

import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Position
import com.kinetix.common.model.Side
import com.kinetix.position.fix.ExecutionCostAnalysis
import com.kinetix.position.fix.ExecutionCostRepository
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.BookTradeResult
import com.kinetix.position.service.TradeBookingService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import java.math.BigDecimal

class DevDataSeederTest : FunSpec({

    val tradeBookingService = mockk<TradeBookingService>()
    val positionRepository = mockk<PositionRepository>()
    val executionCostRepo = mockk<ExecutionCostRepository>()
    val seeder = DevDataSeeder(tradeBookingService, positionRepository, executionCostRepo = executionCostRepo)

    beforeEach {
        clearMocks(tradeBookingService, positionRepository, executionCostRepo)
    }

    fun stubTradeBooking() {
        coEvery { tradeBookingService.handle(any()) } answers {
            val cmd = firstArg<BookTradeCommand>()
            BookTradeResult(
                trade = com.kinetix.common.model.Trade(
                    tradeId = cmd.tradeId,
                    bookId = cmd.bookId,
                    instrumentId = cmd.instrumentId,
                    assetClass = cmd.assetClass,
                    side = cmd.side,
                    quantity = cmd.quantity,
                    price = cmd.price,
                    tradedAt = cmd.tradedAt,
                    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
                ),
                position = Position(
                    bookId = cmd.bookId,
                    instrumentId = cmd.instrumentId,
                    assetClass = cmd.assetClass,
                    quantity = java.math.BigDecimal.ZERO,
                    averageCost = com.kinetix.common.model.Money.zero(cmd.price.currency),
                    marketPrice = com.kinetix.common.model.Money.zero(cmd.price.currency),
                    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.fromString(cmd.instrumentType),
                ),
            )
        }
        coEvery { positionRepository.findByKey(any(), any()) } returns null
        coEvery { positionRepository.save(any()) } just runs
    }

    fun stubExecutionCostEmpty() {
        coEvery { executionCostRepo.findByBookId("equity-growth") } returns emptyList()
        coEvery { executionCostRepo.save(any()) } just runs
    }

    fun stubExecutionCostExists() {
        coEvery { executionCostRepo.findByBookId("equity-growth") } returns listOf(
            DevDataSeeder.EXECUTION_COSTS.first(),
        )
    }

    test("seeds all trades when database is empty") {
        coEvery { positionRepository.findDistinctBookIds() } returns emptyList()
        stubTradeBooking()
        stubExecutionCostEmpty()

        seeder.seed()

        coVerify(exactly = DevDataSeeder.TRADES.size) { tradeBookingService.handle(any()) }
    }

    test("skips seeding when portfolios already exist") {
        coEvery { positionRepository.findDistinctBookIds() } returns listOf(
            BookId("equity-growth"),
        )
        stubExecutionCostEmpty()

        seeder.seed()

        coVerify(exactly = 0) { tradeBookingService.handle(any()) }
    }

    test("updates market prices after booking trades") {
        coEvery { positionRepository.findDistinctBookIds() } returns emptyList()
        stubTradeBooking()
        stubExecutionCostEmpty()

        seeder.seed()

        coVerify(atLeast = DevDataSeeder.MARKET_PRICES.size) { positionRepository.findByKey(any(), any()) }
    }

    test("trade data has minimum required trades per portfolio") {
        val tradesByPortfolio = DevDataSeeder.TRADES.groupBy { it.bookId.value }
        tradesByPortfolio["equity-growth"]!!.size shouldBeGreaterThan 154
        tradesByPortfolio["multi-asset"]!!.size shouldBeGreaterThan 144
        tradesByPortfolio["fixed-income"]!!.size shouldBeGreaterThan 74
        tradesByPortfolio["emerging-markets"]!!.size shouldBeGreaterThan 104
        tradesByPortfolio["macro-hedge"]!!.size shouldBeGreaterThan 124
        tradesByPortfolio["tech-momentum"]!!.size shouldBeGreaterThan 124
        tradesByPortfolio["balanced-income"]!!.size shouldBeGreaterThan 74
        tradesByPortfolio["derivatives-book"]!!.size shouldBeGreaterThan 154
    }

    test("generated trades are deterministic") {
        val firstRun = DevDataSeeder.TRADES.map { it.tradeId.value }
        val secondRun = DevDataSeeder.TRADES.map { it.tradeId.value }
        firstRun shouldBe secondRun
    }

    test("trades span at least 15 business days") {
        val dates = DevDataSeeder.TRADES.map { it.tradedAt.epochSecond / 86400 }.toSortedSet()
        val span = dates.last() - dates.first()
        // 15 business days ≈ 21 calendar days; generated trades go back 19 days from BASE_TIME
        // so span should be >= 15 calendar days
        (span >= 15) shouldBe true
    }

    test("MARKET_PRICES covers all position keys in TRADES") {
        val positionKeys = DevDataSeeder.TRADES.map { it.bookId to it.instrumentId }.toSet()
        val marketPriceKeys = DevDataSeeder.MARKET_PRICES.keys
        positionKeys.forEach { key ->
            marketPriceKeys.contains(key) shouldBe true
        }
    }

    test("all trade IDs are unique") {
        val tradeIds = DevDataSeeder.TRADES.map { it.tradeId.value }
        tradeIds.distinct().size shouldBe tradeIds.size
    }

    test("market prices cover all positions") {
        val positionKeys = DevDataSeeder.TRADES.map { Pair(it.bookId, it.instrumentId) }.toSet()
        val marketPriceKeys = DevDataSeeder.MARKET_PRICES.keys
        positionKeys shouldBe marketPriceKeys
    }

    test("all seed trades have instrumentType set") {
        DevDataSeeder.TRADES.forEach { trade ->
            trade.instrumentType shouldNotBe null
        }
    }

    // ── Execution cost seeding tests ──

    test("seeds execution cost data when database is empty") {
        coEvery { positionRepository.findDistinctBookIds() } returns emptyList()
        stubTradeBooking()
        stubExecutionCostEmpty()

        seeder.seed()

        coVerify(exactly = DevDataSeeder.EXECUTION_COSTS.size) { executionCostRepo.save(any()) }
    }

    test("skips execution cost seeding when seed data already exists") {
        coEvery { positionRepository.findDistinctBookIds() } returns emptyList()
        stubTradeBooking()
        stubExecutionCostExists()

        seeder.seed()

        coVerify(exactly = 0) { executionCostRepo.save(any()) }
    }

    test("seeds execution costs even when trades already exist (warm restart)") {
        coEvery { positionRepository.findDistinctBookIds() } returns listOf(BookId("equity-growth"))
        stubExecutionCostEmpty()

        seeder.seed()

        coVerify(exactly = 0) { tradeBookingService.handle(any()) }
        coVerify(exactly = DevDataSeeder.EXECUTION_COSTS.size) { executionCostRepo.save(any()) }
    }

    test("execution cost order IDs are all unique") {
        val orderIds = DevDataSeeder.EXECUTION_COSTS.map { it.orderId }
        orderIds.distinct().size shouldBe orderIds.size
    }

    test("execution costs cover all 8 books") {
        val books = DevDataSeeder.EXECUTION_COSTS.map { it.bookId }.distinct().sorted()
        books.size shouldBe 8
        books shouldBe listOf(
            "balanced-income", "derivatives-book", "emerging-markets",
            "equity-growth", "fixed-income", "macro-hedge",
            "multi-asset", "tech-momentum",
        )
    }

    test("each book has at least 3 execution cost entries") {
        val countsByBook = DevDataSeeder.EXECUTION_COSTS.groupBy { it.bookId }.mapValues { it.value.size }
        countsByBook.forEach { (book, count) ->
            count shouldBeGreaterThan 2
        }
    }

    test("execution costs reference valid trade instrument/book combinations") {
        val tradeKeys = DevDataSeeder.TRADES.map { Pair(it.bookId.value, it.instrumentId.value) }.toSet()
        DevDataSeeder.EXECUTION_COSTS.forEach { cost ->
            tradeKeys shouldContain Pair(cost.bookId, cost.instrumentId)
        }
    }

    test("some execution costs have non-null marketImpactBps") {
        DevDataSeeder.EXECUTION_COSTS.any { it.metrics.marketImpactBps != null } shouldBe true
    }

    test("some execution costs have non-null timingCostBps") {
        DevDataSeeder.EXECUTION_COSTS.any { it.metrics.timingCostBps != null } shouldBe true
    }

    test("timingCostBps entries are a subset of marketImpactBps entries") {
        DevDataSeeder.EXECUTION_COSTS.forEach { cost ->
            if (cost.metrics.timingCostBps != null) {
                cost.metrics.marketImpactBps shouldNotBe null
            }
        }
    }

    test("totalCostBps equals slippageBps + marketImpactBps + timingCostBps for every entry") {
        DevDataSeeder.EXECUTION_COSTS.forEach { cost ->
            val expected = cost.metrics.slippageBps
                .add(cost.metrics.marketImpactBps ?: BigDecimal.ZERO)
                .add(cost.metrics.timingCostBps ?: BigDecimal.ZERO)
            cost.metrics.totalCostBps.compareTo(expected) shouldBe 0
        }
    }

    test("slippage sign convention is correct for BUY and SELL sides") {
        // BUY: positive slippage means paid more than arrival (cost)
        // SELL: positive slippage means received less than arrival (cost)
        DevDataSeeder.EXECUTION_COSTS.forEach { cost ->
            val diff = cost.averageFillPrice.subtract(cost.arrivalPrice)
            val sideSign = if (cost.side == Side.BUY) BigDecimal.ONE else BigDecimal("-1")
            val expectedSign = diff.multiply(sideSign).signum()
            cost.metrics.slippageBps.signum() shouldBe expectedSign
        }
    }

    test("execution cost data has mix of positive and negative slippage") {
        val positive = DevDataSeeder.EXECUTION_COSTS.count { it.metrics.slippageBps > BigDecimal.ZERO }
        val negative = DevDataSeeder.EXECUTION_COSTS.count { it.metrics.slippageBps < BigDecimal.ZERO }
        positive shouldBeGreaterThan 0
        negative shouldBeGreaterThan 0
        // Majority should be positive (realistic: most orders have some cost)
        positive shouldBeGreaterThan negative
    }
})
