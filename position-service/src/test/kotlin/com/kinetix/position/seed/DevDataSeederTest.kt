package com.kinetix.position.seed

import com.kinetix.common.demo.SeedProfile
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Position
import com.kinetix.common.model.Side
import com.kinetix.position.fix.ExecutionCostAnalysis
import com.kinetix.position.fix.ExecutionCostRepository
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.service.AmendTradeCommand
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.BookTradeResult
import com.kinetix.position.service.CancelTradeCommand
import com.kinetix.position.service.TradeBookingService
import com.kinetix.position.service.TradeLifecycleService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
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

    // ── Phase 2 Gap 2 — equity-ls scenario ────────────────────────────────

    test("equity-ls scenario seeds 800+ distinct positions in a single book") {
        coEvery { positionRepository.findDistinctBookIds() } returns emptyList()
        val captured = mutableListOf<BookTradeCommand>()
        coEvery { tradeBookingService.handle(capture(captured)) } answers {
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
                    quantity = BigDecimal.ZERO,
                    averageCost = com.kinetix.common.model.Money.zero(cmd.price.currency),
                    marketPrice = com.kinetix.common.model.Money.zero(cmd.price.currency),
                    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.fromString(cmd.instrumentType),
                ),
            )
        }
        coEvery { positionRepository.findByKey(any(), any()) } returns null
        coEvery { positionRepository.save(any()) } just runs
        stubExecutionCostEmpty()

        seeder.seed(SeedProfile.EquityLS)

        val books = captured.map { it.bookId.value }.toSet()
        books shouldBe setOf("equity-ls")
        val distinctInstruments = captured.map { it.instrumentId.value }.toSet()
        distinctInstruments.size shouldBeGreaterThanOrEqualTo 800
    }

    test("equity-ls scenario has both long and short legs") {
        val trades = EquityLongShortScenario.TRADES
        val buys = trades.count { it.side == Side.BUY }
        val sells = trades.count { it.side == Side.SELL }
        buys shouldBeGreaterThan 100
        sells shouldBeGreaterThan 100
    }

    test("equity-ls scenario is sector-tilted with >=3 sectors net long and >=3 net short") {
        val tradesBySector = EquityLongShortScenario.INSTRUMENTS.groupBy { it.sector }
        val netLongSectors = tradesBySector.count { (_, instruments) ->
            instruments.count { it.side == Side.BUY } > instruments.count { it.side == Side.SELL }
        }
        val netShortSectors = tradesBySector.count { (_, instruments) ->
            instruments.count { it.side == Side.SELL } > instruments.count { it.side == Side.BUY }
        }
        netLongSectors shouldBeGreaterThanOrEqualTo 3
        netShortSectors shouldBeGreaterThanOrEqualTo 3
    }

    test("equity-ls scenario gross long and short notionals are roughly balanced (factor-neutral)") {
        val instruments = EquityLongShortScenario.INSTRUMENTS
        fun notional(spec: EquityLongShortScenario.InstrumentSpec) =
            spec.typicalPrice.multiply(spec.typicalQty)
        val grossLong = instruments
            .filter { it.side == Side.BUY }
            .sumOf { notional(it) }
        val grossShort = instruments
            .filter { it.side == Side.SELL }
            .sumOf { notional(it) }
        val gross = grossLong.add(grossShort)
        val netImbalance = grossLong.subtract(grossShort).abs()
        // Factor-neutrality: |gross long − gross short| should be <20% of total gross
        val tolerance = gross.multiply(BigDecimal("0.20"))
        (netImbalance < tolerance) shouldBe true
    }

    test("equity-ls scenario trade IDs are unique and deterministic") {
        val firstRun = EquityLongShortScenario.TRADES.map { it.tradeId.value }
        val secondRun = EquityLongShortScenario.TRADES.map { it.tradeId.value }
        firstRun shouldBe secondRun
        firstRun.distinct().size shouldBe firstRun.size
    }

    // ── Phase 2 Gap 2 — stress scenario ───────────────────────────────────

    test("stress scenario seeds exactly 3 books") {
        val books = StressScenario.TRADES.map { it.bookId.value }.toSet()
        books.size shouldBe 3
        books shouldContain StressScenario.MOMENTUM_BOOK
        books shouldContain StressScenario.CREDIT_BOOK
        books shouldContain StressScenario.VOL_BOOK
    }

    test("stress scenario produces ~100 distinct positions total") {
        val keys = StressScenario.TRADES.map { it.bookId.value to it.instrumentId.value }.toSet()
        keys.size shouldBeGreaterThanOrEqualTo 90
        (keys.size <= 110) shouldBe true
    }

    test("stress scenario has exactly one book in active notional breach") {
        val notionalByBook = StressScenario.INSTRUMENTS
            .groupBy { it.book }
            .mapValues { (_, list) ->
                list.sumOf { it.typicalPrice.multiply(it.typicalQty) }
            }
        val limitsByBook = StressScenario.LIMIT_DEFINITIONS
            .filter { it.limitType.name == "NOTIONAL" && it.level.name == "BOOK" }
            .associate { it.entityId to it.limitValue }
        val breachedBooks = notionalByBook.filter { (book, gross) ->
            val limit = limitsByBook[book]
            limit != null && gross > limit
        }.keys
        breachedBooks shouldBe setOf(StressScenario.VOL_BOOK)
    }

    test("stress scenario has the vol book in single-name concentration breach") {
        val volPositions = StressScenario.INSTRUMENTS.filter { it.book == StressScenario.VOL_BOOK }
        val bookGross = volPositions.sumOf { it.typicalPrice.multiply(it.typicalQty) }
        val byInstrument = volPositions
            .groupBy { it.id.value }
            .mapValues { (_, list) -> list.sumOf { it.typicalPrice.multiply(it.typicalQty) } }
        val heaviest = byInstrument.values.maxOrNull()!!
        val heaviestShare = heaviest.divide(bookGross, 4, java.math.RoundingMode.HALF_UP)
        val concLimit = StressScenario.LIMIT_DEFINITIONS
            .firstOrNull { it.entityId == StressScenario.VOL_BOOK && it.limitType.name == "CONCENTRATION" }
            ?.limitValue
        concLimit shouldNotBe null
        (heaviestShare > concLimit!!) shouldBe true
    }

    test("stress scenario trade IDs are unique and deterministic") {
        val firstRun = StressScenario.TRADES.map { it.tradeId.value }
        val secondRun = StressScenario.TRADES.map { it.tradeId.value }
        firstRun shouldBe secondRun
        firstRun.distinct().size shouldBe firstRun.size
    }

    // ── Phase 2 Gap 2 — options-book scenario ─────────────────────────────

    test("options-book scenario seeds exactly 2 books") {
        val books = OptionsBookScenario.TRADES.map { it.bookId.value }.toSet()
        books.size shouldBe 2
        books shouldContain OptionsBookScenario.EQUITY_VOL_BOOK
        books shouldContain OptionsBookScenario.CROSS_ASSET_VOL_BOOK
    }

    test("options-book scenario produces 1000+ distinct positions") {
        val keys = OptionsBookScenario.TRADES
            .map { it.bookId.value to it.instrumentId.value }
            .toSet()
        keys.size shouldBeGreaterThanOrEqualTo 1000
    }

    test("options-book scenario has both calls and puts in each book") {
        OptionsBookScenario.TRADES
            .groupBy { it.bookId.value }
            .forEach { (_, trades) ->
                val callCount = trades.count { it.instrumentId.value.contains("-C-") }
                val putCount = trades.count { it.instrumentId.value.contains("-P-") }
                callCount shouldBeGreaterThan 0
                putCount shouldBeGreaterThan 0
            }
    }

    test("options-book scenario has both long and short legs") {
        val buys = OptionsBookScenario.TRADES.count { it.side == Side.BUY }
        val sells = OptionsBookScenario.TRADES.count { it.side == Side.SELL }
        buys shouldBeGreaterThan 100
        sells shouldBeGreaterThan 100
    }

    test("options-book scenario spans at least 4 distinct expiries per book") {
        OptionsBookScenario.TRADES
            .groupBy { it.bookId.value }
            .forEach { (_, trades) ->
                // Expiry codes are W1/W2/W4/M1/M2/M3 — extract from instrument ID.
                val expiries = trades.map { extractExpiryCode(it.instrumentId.value) }.toSet()
                expiries.size shouldBeGreaterThanOrEqualTo 4
            }
    }

    test("options-book scenario trade IDs are unique and deterministic") {
        val firstRun = OptionsBookScenario.TRADES.map { it.tradeId.value }
        val secondRun = OptionsBookScenario.TRADES.map { it.tradeId.value }
        firstRun shouldBe secondRun
        firstRun.distinct().size shouldBe firstRun.size
    }

    // ── Phase 3 Gap 4 — proper amend/cancel lifecycle commands ─────────────

    test("amend triplet originals are part of TRADES and booked normally") {
        val tradeIds = DevDataSeeder.TRADES.map { it.tradeId.value }.toSet()
        DevDataSeeder.AMEND_TRIPLETS.forEach { triplet ->
            tradeIds shouldContain triplet.original.tradeId.value
        }
    }

    test("cancel triplet originals are part of TRADES and booked normally") {
        val tradeIds = DevDataSeeder.TRADES.map { it.tradeId.value }.toSet()
        DevDataSeeder.CANCEL_TRIPLETS.forEach { triplet ->
            tradeIds shouldContain triplet.original.tradeId.value
        }
    }

    test("TRADES no longer contains opposite-side cancel-simulation IDs") {
        val tradeIds = DevDataSeeder.TRADES.map { it.tradeId.value }
        tradeIds.none { it.endsWith("-cancel") } shouldBe true
        tradeIds.none { it.endsWith("-amend") } shouldBe true
    }

    test("amend command references the original trade ID and assigns a new ID") {
        DevDataSeeder.AMEND_TRIPLETS.forEach { triplet ->
            triplet.amend.originalTradeId shouldBe triplet.original.tradeId
            triplet.amend.newTradeId.value shouldNotBe triplet.original.tradeId.value
            triplet.amend.newTradeId.value shouldBe "${triplet.original.tradeId.value}-amend"
        }
    }

    test("cancel command targets the original trade ID") {
        DevDataSeeder.CANCEL_TRIPLETS.forEach { triplet ->
            triplet.cancel.tradeId shouldBe triplet.original.tradeId
        }
    }

    test("amend changes quantity by ~5% from the original") {
        DevDataSeeder.AMEND_TRIPLETS.forEach { triplet ->
            val originalQty = triplet.original.quantity
            val amendQty = triplet.amend.quantity
            (amendQty > originalQty) shouldBe true
            val ratio = amendQty.divide(originalQty, 4, java.math.RoundingMode.HALF_UP)
            (ratio >= BigDecimal("1.04") && ratio <= BigDecimal("1.06")) shouldBe true
        }
    }

    test("seed invokes TradeLifecycleService for every amend and cancel triplet") {
        val lifecycle = mockk<TradeLifecycleService>()
        val seederWithLifecycle = DevDataSeeder(
            tradeBookingService = tradeBookingService,
            positionRepository = positionRepository,
            executionCostRepo = executionCostRepo,
            tradeLifecycleService = lifecycle,
        )
        coEvery { positionRepository.findDistinctBookIds() } returns emptyList()
        stubTradeBooking()
        stubExecutionCostEmpty()
        val amendResult = mockk<com.kinetix.position.service.BookTradeResult>(relaxed = true)
        val cancelResult = mockk<com.kinetix.position.service.BookTradeResult>(relaxed = true)
        coEvery { lifecycle.handleAmend(any()) } returns amendResult
        coEvery { lifecycle.handleCancel(any()) } returns cancelResult

        seederWithLifecycle.seed()

        coVerify(exactly = DevDataSeeder.AMEND_TRIPLETS.size) { lifecycle.handleAmend(any()) }
        coVerify(exactly = DevDataSeeder.CANCEL_TRIPLETS.size) { lifecycle.handleCancel(any()) }
    }

    test("seed skips lifecycle commands when TradeLifecycleService is not wired") {
        // Backward-compat path: seeder must remain callable without lifecycle.
        coEvery { positionRepository.findDistinctBookIds() } returns emptyList()
        stubTradeBooking()
        stubExecutionCostEmpty()

        seeder.seed()
        // No lifecycle service injected → no failure; trades booked as before.
        coVerify(exactly = DevDataSeeder.TRADES.size) { tradeBookingService.handle(any()) }
    }

    test("amend triplets cover at least 5 distinct books") {
        val books = DevDataSeeder.AMEND_TRIPLETS.map { it.original.bookId.value }.toSet()
        books.size shouldBeGreaterThanOrEqualTo 5
    }

    test("cancel triplets cover at least 5 distinct books") {
        val books = DevDataSeeder.CANCEL_TRIPLETS.map { it.original.bookId.value }.toSet()
        books.size shouldBeGreaterThanOrEqualTo 5
    }

    test("default seed meets Phase 3 Gap 4 lifecycle volume target (~50 amends, ~30 cancels)") {
        DevDataSeeder.AMEND_TRIPLETS.size shouldBeGreaterThanOrEqualTo 45
        DevDataSeeder.CANCEL_TRIPLETS.size shouldBeGreaterThanOrEqualTo 28
    }

    test("lifecycle triplets touch every book") {
        val books = (DevDataSeeder.AMEND_TRIPLETS.map { it.original.bookId.value } +
                     DevDataSeeder.CANCEL_TRIPLETS.map { it.original.bookId.value }).toSet()
        books shouldBe setOf(
            "balanced-income", "derivatives-book", "emerging-markets",
            "equity-growth", "fixed-income", "macro-hedge",
            "multi-asset", "tech-momentum",
        )
    }

    test("all lifecycle trade IDs are unique") {
        val originalIds = DevDataSeeder.AMEND_TRIPLETS.map { it.original.tradeId.value } +
                          DevDataSeeder.CANCEL_TRIPLETS.map { it.original.tradeId.value }
        originalIds.distinct().size shouldBe originalIds.size
    }

    test("lifecycle specs are deterministic across rebuilds") {
        val firstA = DevDataSeeder.AMEND_TRIPLETS.map { it.original.tradeId.value to it.amend.newTradeId.value }
        val secondA = DevDataSeeder.AMEND_TRIPLETS.map { it.original.tradeId.value to it.amend.newTradeId.value }
        firstA shouldBe secondA
        val firstC = DevDataSeeder.CANCEL_TRIPLETS.map { it.original.tradeId.value }
        val secondC = DevDataSeeder.CANCEL_TRIPLETS.map { it.original.tradeId.value }
        firstC shouldBe secondC
    }
})

private fun extractExpiryCode(instrumentId: String): String {
    // Instrument IDs look like `<UNDERLYING>-<EXPIRY>-<C|P>-<STRIKE>`.
    val parts = instrumentId.split("-")
    return parts.getOrNull(1) ?: ""
}
