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

    fun stubResolver(): StrategyIdResolver = object : StrategyIdResolver {
        override fun strategyIdFor(bookId: String): String = "$bookId-strategy"
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

    test("generated notional always lies inside the profile's notionalRangeUsd") {
        // Capture every generated quantity, recover the notional via the
        // hardcoded EQUITY price ($100), and assert it lies in range.
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<StrategyTradeRequest>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += thirdArg<StrategyTradeRequest>()
            "ok-${captured.size}"
        }

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
})
