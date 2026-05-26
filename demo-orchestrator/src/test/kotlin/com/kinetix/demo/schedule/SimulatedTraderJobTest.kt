package com.kinetix.demo.schedule

import com.kinetix.demo.client.PositionServiceClient
import com.kinetix.demo.client.dtos.StrategyTradeRequest
import com.kinetix.demo.profile.DemoBookProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Unit tests for [SimulatedTraderJob].
 *
 * Behaviour is gated on UTC trading hours and Mon-Fri. We freeze [Clock] to a
 * known instant and seed [Random] so the assertions are deterministic — the
 * job picks instruments, sides, and notionals via the injected RNG.
 */
class SimulatedTraderJobTest : FunSpec({

    val tradingHoursStart: LocalTime = LocalTime.of(9, 0)
    val tradingHoursEnd: LocalTime = LocalTime.of(16, 30)

    fun fixedClock(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    fun atUtc(date: String, time: String): Instant =
        Instant.parse("${date}T${time}Z")

    val sampleEquityBook = DemoBookProfile(
        bookId = "equity-growth",
        tradeProbability = 1.0,
        instrumentIds = listOf("AAPL", "MSFT"),
        notionalRangeUsd = 25_000L..500_000L,
        assetClass = "EQUITY",
    )

    val sampleFxBook = DemoBookProfile(
        bookId = "macro-hedge",
        tradeProbability = 1.0,
        instrumentIds = listOf("EURUSD", "USDJPY"),
        notionalRangeUsd = 250_000L..5_000_000L,
        assetClass = "FX",
    )

    fun stubResolver(
        strategiesByBook: Map<String, List<String>> = emptyMap(),
    ): StrategyIdResolver = object : StrategyIdResolver {
        override suspend fun strategiesFor(bookId: String): List<String> =
            strategiesByBook[bookId] ?: listOf("$bookId-strategy")
    }

    test("Saturday is a no-op — no trades are posted") {
        val client = mockk<PositionServiceClient>(relaxed = true)
        // 2026-05-16 is a Saturday.
        val clock = fixedClock(atUtc("2026-05-16", "14:00:00"))

        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(sampleEquityBook, sampleFxBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 42),
        )

        runTest {
            job.runTick() shouldBe 0
        }

        coVerify(exactly = 0) { client.bookTrade(any(), any(), any()) }
    }

    test("Sunday is a no-op — no trades are posted") {
        val client = mockk<PositionServiceClient>(relaxed = true)
        // 2026-05-17 is a Sunday.
        val clock = fixedClock(atUtc("2026-05-17", "10:00:00"))

        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(sampleEquityBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 42),
        )

        runTest {
            job.runTick() shouldBe 0
        }

        coVerify(exactly = 0) { client.bookTrade(any(), any(), any()) }
    }

    test("pre-open weekday tick is a no-op") {
        val client = mockk<PositionServiceClient>(relaxed = true)
        // 2026-05-18 is a Monday.
        val clock = fixedClock(atUtc("2026-05-18", "06:00:00"))

        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(sampleEquityBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 42),
        )

        runTest {
            job.runTick() shouldBe 0
        }

        coVerify(exactly = 0) { client.bookTrade(any(), any(), any()) }
    }

    test("post-close weekday tick is a no-op") {
        val client = mockk<PositionServiceClient>(relaxed = true)
        val clock = fixedClock(atUtc("2026-05-18", "17:00:00"))

        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(sampleEquityBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 42),
        )

        runTest {
            job.runTick() shouldBe 0
        }

        coVerify(exactly = 0) { client.bookTrade(any(), any(), any()) }
    }

    test("in-hours weekday tick posts profile-respecting trades for every book") {
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<Triple<String, String, StrategyTradeRequest>>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += Triple(
                firstArg<String>(),
                secondArg<String>(),
                thirdArg<StrategyTradeRequest>(),
            )
            "generated-trade-id-${captured.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val instant = atUtc("2026-05-18", "12:00:00")
        val clock = fixedClock(instant)
        val books = listOf(sampleEquityBook, sampleFxBook)

        var postedRef = 0
        runTest {
            postedRef = SimulatedTraderJob(
                positionClient = client,
                strategyIdResolver = stubResolver(),
                books = books,
                tradingHoursStart = tradingHoursStart,
                tradingHoursEnd = tradingHoursEnd,
                clock = clock,
                random = Random(seed = 7),
            ).runTick()
        }
        val posted: Int = postedRef

        // Both books had probability 1.0, so each generated 1..3 trades.
        captured.size shouldBe posted
        posted shouldBeGreaterThan 0

        val bookIds = books.associateBy { it.bookId }
        for ((bookId, strategyId, request) in captured) {
            val profile = bookIds.getValue(bookId)
            strategyId shouldBe "$bookId-strategy"
            request.instrumentId shouldBeIn profile.instrumentIds
            request.assetClass shouldBe profile.assetClass
            listOf("BUY", "SELL") shouldContain request.side
            request.priceCurrency shouldBe "USD"
            request.userId shouldBe "demo-orchestrator"
            request.userRole shouldBe "DEMO"
            request.tradeId shouldBe null

            val quantity = request.quantity.toLong()
            (quantity >= 1L) shouldBe true

            val priceAmount = BigDecimal(request.priceAmount)
            (priceAmount > BigDecimal.ZERO) shouldBe true

            Instant.parse(request.tradedAt) shouldBe instant
        }

        // Per-book counts each fall in [1, 3].
        captured.groupBy { it.first }.forEach { (_, trades) ->
            trades.size shouldBeGreaterThanOrEqual 1
            trades.size shouldBeLessThanOrEqual 3
        }
    }

    test("tradeProbability == 0.0 emits no trades even in-hours") {
        val client = mockk<PositionServiceClient>(relaxed = true)
        val clock = fixedClock(atUtc("2026-05-18", "12:00:00"))

        val zeroProbBook = sampleEquityBook.copy(tradeProbability = 0.0)
        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(zeroProbBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 1),
        )

        runTest { job.runTick() shouldBe 0 }
        coVerify(exactly = 0) { client.bookTrade(any(), any(), any()) }
    }

    test("returns the count of successful posts when one bookTrade call throws") {
        val client = mockk<PositionServiceClient>()
        var calls = 0
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            calls += 1
            if (calls == 1) {
                throw RuntimeException("position-service down for trade #$calls")
            }
            "ok-$calls"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val clock = fixedClock(atUtc("2026-05-18", "12:00:00"))
        var postedRef = 0
        runTest {
            postedRef = SimulatedTraderJob(
                positionClient = client,
                strategyIdResolver = stubResolver(),
                books = listOf(sampleEquityBook),
                tradingHoursStart = tradingHoursStart,
                tradingHoursEnd = tradingHoursEnd,
                clock = clock,
                random = Random(seed = 7),
            ).runTick()
        }
        val posted = postedRef

        // First call failed; remaining attempts succeed → posted = calls - 1.
        (calls - posted) shouldBe 1
        posted shouldBeGreaterThanOrEqual 0
    }

    test("an execution-cost sample is seeded for every successfully booked trade") {
        val client = mockk<PositionServiceClient>()
        val bookedTrades = mutableListOf<StrategyTradeRequest>()
        val recordedCosts = mutableListOf<com.kinetix.demo.client.dtos.RecordExecutionCostRequest>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            bookedTrades += thirdArg<StrategyTradeRequest>()
            "trade-${bookedTrades.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } answers {
            recordedCosts += secondArg<com.kinetix.demo.client.dtos.RecordExecutionCostRequest>()
        }
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val clock = fixedClock(atUtc("2026-05-18", "12:00:00"))
        runTest {
            SimulatedTraderJob(
                positionClient = client,
                strategyIdResolver = stubResolver(),
                books = listOf(sampleEquityBook),
                tradingHoursStart = tradingHoursStart,
                tradingHoursEnd = tradingHoursEnd,
                clock = clock,
                random = Random(seed = 7),
            ).runTick()
        }

        // One execution-cost sample per booked trade.
        recordedCosts.size shouldBe bookedTrades.size
        recordedCosts.size shouldBeGreaterThan 0
        recordedCosts.forEach { cost ->
            cost.instrumentId shouldBeIn sampleEquityBook.instrumentIds
            listOf("BUY", "SELL") shouldContain cost.side
            cost.orderId.startsWith("demo-ord-") shouldBe true
            // totalCostBps is parseable as a number.
            BigDecimal(cost.totalCostBps)
        }
    }

    test("reconciliation breaks are seeded for roughly the configured fraction of trades") {
        val client = mockk<PositionServiceClient>()
        var booked = 0
        var statements = 0
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            booked += 1
            "trade-$booked"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } answers {
            statements += 1
        }

        // Force every booked trade to also upload a mismatched statement by
        // setting the break probability to 1.0 — deterministic, no RNG slack.
        val clock = fixedClock(atUtc("2026-05-18", "12:00:00"))
        runTest {
            SimulatedTraderJob(
                positionClient = client,
                strategyIdResolver = stubResolver(),
                books = listOf(sampleEquityBook),
                tradingHoursStart = tradingHoursStart,
                tradingHoursEnd = tradingHoursEnd,
                clock = clock,
                random = Random(seed = 7),
                reconciliationBreakProbability = 1.0,
            ).runTick()
        }

        booked shouldBeGreaterThan 0
        statements shouldBe booked
    }

    test("no reconciliation statement is uploaded when the break probability is zero") {
        val client = mockk<PositionServiceClient>()
        coEvery { client.bookTrade(any(), any(), any()) } returns "trade-x"
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val clock = fixedClock(atUtc("2026-05-18", "12:00:00"))
        runTest {
            SimulatedTraderJob(
                positionClient = client,
                strategyIdResolver = stubResolver(),
                books = listOf(sampleEquityBook),
                tradingHoursStart = tradingHoursStart,
                tradingHoursEnd = tradingHoursEnd,
                clock = clock,
                random = Random(seed = 7),
                reconciliationBreakProbability = 0.0,
            ).runTick()
        }

        coVerify(exactly = 0) { client.uploadPrimeBrokerStatement(any(), any()) }
    }

    test("rotates trades across all 6 demo counterparties over a few hundred ticks (kx-i72)") {
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<String?>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += thirdArg<StrategyTradeRequest>().counterpartyId
            "trade-${captured.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val highFreqBook = DemoBookProfile(
            bookId = "high-freq",
            tradeProbability = 1.0,
            instrumentIds = listOf("AAPL"),
            notionalRangeUsd = 25_000L..500_000L,
            assetClass = "EQUITY",
        )
        val clock = fixedClock(atUtc("2026-05-18", "12:00:00"))
        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(highFreqBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 7),
        )

        runTest {
            // 200 ticks × ~1-3 trades each = plenty to exercise the 6-cp rotation.
            repeat(200) { job.runTick() }
        }

        captured.size shouldBeGreaterThan 0
        val distinctCps = captured.filterNotNull().toSet()
        // The simulator rotates through the 6 canonical demo counterparties —
        // not the issue's literal {GS, JPM, MS, CS, BoA, Citi} (the codebase
        // standardises on the existing G-SIB ids in CounterpartyTiers).
        distinctCps shouldBe setOf("CP-GS", "CP-JPM", "CP-BARC", "CP-DB", "CP-UBS", "CP-CITI")
    }

    test("counterparty rotation is round-robin per book — first 6 trades of a book hit all 6 distinct CPs") {
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<String?>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += thirdArg<StrategyTradeRequest>().counterpartyId
            "trade-${captured.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val clock = fixedClock(atUtc("2026-05-18", "12:00:00"))
        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(sampleEquityBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 7),
            // Inject a tiny pool so the assertion does not depend on the
            // canonical 6-id list and the test stays fast.
            counterpartyRotation = CounterpartyRotation(listOf("CP-A", "CP-B", "CP-C")),
        )

        runTest {
            // 3 ticks usually yields 3-9 trades — enough to wrap the 3-cp pool.
            repeat(3) { job.runTick() }
        }

        val first3 = captured.take(3)
        first3 shouldBe listOf("CP-A", "CP-B", "CP-C")
    }

    test("generated notional always lies inside the profile's notionalRangeUsd") {
        // Capture every generated quantity, recover the notional via the
        // hardcoded EQUITY price ($100), and assert it lies in range.
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<StrategyTradeRequest>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += thirdArg<StrategyTradeRequest>()
            "ok-${captured.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val tightBook = DemoBookProfile(
            bookId = "tight-equity",
            tradeProbability = 1.0,
            instrumentIds = listOf("AAPL"),
            notionalRangeUsd = 100_000L..200_000L,
            assetClass = "EQUITY",
        )
        val clock = fixedClock(atUtc("2026-05-18", "12:00:00"))
        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(tightBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 17),
        )

        runTest { job.runTick() }

        captured.forEach { request ->
            // EQUITY default price is $100.00 → notional ≈ quantity * 100. The
            // quantity rounds HALF_UP so allow a 1-unit tolerance either side.
            val notional = request.quantity.toLong() * 100L
            (notional >= tightBook.notionalRangeUsd.first - 100L) shouldBe true
            (notional <= tightBook.notionalRangeUsd.last + 100L) shouldBe true
        }
    }

    test("DEFAULT_RECONCILIATION_BREAK_PROBABILITY is 1% — demo breaks are rare") {
        // Earlier seed value of 5% produced too many reconciliation breaks
        // for a realistic demo; kx-3rm lowers the rate to 1%.
        SimulatedTraderJob.DEFAULT_RECONCILIATION_BREAK_PROBABILITY shouldBe 0.01
    }

    test("US market holiday weekday is a no-op — no trades are posted") {
        // 2026-07-03 is a Friday — but Independence Day is observed on it
        // because July 4 falls on a Saturday. The job must skip it even
        // though it falls inside the weekday trading-hours window.
        val client = mockk<PositionServiceClient>(relaxed = true)
        val clock = fixedClock(atUtc("2026-07-03", "14:00:00"))

        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(sampleEquityBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 42),
        )

        runTest { job.runTick() shouldBe 0 }
        coVerify(exactly = 0) { client.bookTrade(any(), any(), any()) }
    }

    test("Thanksgiving 2026 is a no-op — no trades are posted") {
        // 2026-11-26 is the fourth Thursday of November — Thanksgiving.
        val client = mockk<PositionServiceClient>(relaxed = true)
        val clock = fixedClock(atUtc("2026-11-26", "14:00:00"))

        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(sampleEquityBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 42),
        )

        runTest { job.runTick() shouldBe 0 }
        coVerify(exactly = 0) { client.bookTrade(any(), any(), any()) }
    }

    test("distributes trades across all strategies in a book over many ticks") {
        // Over enough ticks the trader should hit every strategy at least
        // once — verifies the uniform-random pick is not collapsing to one.
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<Triple<String, String, StrategyTradeRequest>>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += Triple(firstArg<String>(), secondArg<String>(), thirdArg<StrategyTradeRequest>())
            "trade-${captured.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val strategies = listOf("equity-growth-core", "equity-growth-satellite")
        val resolver = stubResolver(strategiesByBook = mapOf("equity-growth" to strategies))
        val clock = fixedClock(atUtc("2026-05-18", "12:00:00"))
        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = resolver,
            books = listOf(sampleEquityBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 11),
        )

        runTest {
            // 50 ticks at trade-prob=1.0 → 50..150 booked trades.
            repeat(50) { job.runTick() }
        }

        captured.size shouldBeGreaterThan 0
        val byStrategy = captured.groupingBy { it.second }.eachCount()
        // Every strategy was used at least once → distribution is real, not
        // collapsed to a single id.
        byStrategy.keys shouldBe strategies.toSet()
        byStrategy.values.forEach { count -> count shouldBeGreaterThan 0 }
    }

    test("picks the single strategy when a book has only one") {
        // Backward-compat path: resolver returns a single-element list →
        // every trade goes there, just like the legacy resolver behaviour.
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<String>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += secondArg<String>()
            "trade-${captured.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val resolver = stubResolver(strategiesByBook = mapOf("equity-growth" to listOf("only-one")))
        val clock = fixedClock(atUtc("2026-05-18", "12:00:00"))
        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = resolver,
            books = listOf(sampleEquityBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = clock,
            random = Random(seed = 7),
        )

        runTest { job.runTick() }

        captured.size shouldBeGreaterThan 0
        captured.toSet() shouldBe setOf("only-one")
    }
})
