package com.kinetix.demo.schedule

import com.kinetix.demo.client.PositionServiceClient
import com.kinetix.demo.client.dtos.StrategyTradeRequest
import com.kinetix.demo.profile.DemoBookProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Per-instrument taxonomy regression — kx-trader-review P0 #3.
 *
 * The trader-review walkthrough on the live demo observed that the
 * `multi-asset` and `balanced-income` demo books (both tagged
 * `assetClass = "EQUITY"`) include Treasury identifiers `UST-5Y` and
 * `UST-10Y`. The `SimulatedTraderJob` then inherits the *book-level*
 * `assetClass` tag onto every trade it books — so a UST-10Y trade in the
 * `balanced-income` book is sent to `position-service` as
 * `assetClass=EQUITY` / `instrumentType=CASH_EQUITY`, which is then
 * what the Trades blotter and Risk → Position Risk Breakdown render.
 *
 * The fix is to look up the *instrument's* asset class and instrument
 * type rather than blindly inheriting the book's tag. This test fixes a
 * book where the per-book tag is EQUITY but the instrument list is a
 * Treasury, and asserts the resulting trade request carries
 * FIXED_INCOME / GOVERNMENT_BOND. AAPL is included alongside in the
 * same book to assert the discrimination — equities still go through as
 * EQUITY / CASH_EQUITY.
 */
class SimulatedTraderInstrumentTaxonomyTest : FunSpec({

    val tradingHoursStart: LocalTime = LocalTime.of(9, 0)
    val tradingHoursEnd: LocalTime = LocalTime.of(16, 30)

    fun fixedClock(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)
    fun atUtc(date: String, time: String): Instant = Instant.parse("${date}T${time}Z")

    fun stubResolver(): StrategyIdResolver = object : StrategyIdResolver {
        override suspend fun strategiesFor(bookId: String): List<String> = listOf("$bookId-strategy")
    }

    test("UST-10Y in an EQUITY-tagged book is booked as FIXED_INCOME / GOVERNMENT_BOND (not CASH_EQUITY)") {
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<StrategyTradeRequest>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += thirdArg<StrategyTradeRequest>()
            "trade-${captured.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        // The bug surface: a book tagged `assetClass = "EQUITY"` whose
        // instrument list contains only Treasury identifiers. Every trade
        // booked by this job must carry FIXED_INCOME / GOVERNMENT_BOND
        // taxonomy on the wire, NOT the book's EQUITY / CASH_EQUITY tag.
        val mixedTreasuryEquityBook = DemoBookProfile(
            bookId = "balanced-income",
            tradeProbability = 1.0,
            instrumentIds = listOf("UST-10Y"),
            notionalRangeUsd = 100_000L..200_000L,
            assetClass = "EQUITY",
        )

        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(mixedTreasuryEquityBook),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = fixedClock(atUtc("2026-05-18", "12:00:00")),
            random = Random(seed = 7),
        )

        runTest { job.runTick() }

        captured.isNotEmpty() shouldBe true
        captured.forEach { request ->
            request.instrumentId shouldBe "UST-10Y"
            request.assetClass shouldBe "FIXED_INCOME"
            request.instrumentType shouldBe "GOVERNMENT_BOND"
        }
    }

    test("UST-5Y in an EQUITY-tagged book is booked as FIXED_INCOME / GOVERNMENT_BOND") {
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<StrategyTradeRequest>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += thirdArg<StrategyTradeRequest>()
            "trade-${captured.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val book = DemoBookProfile(
            bookId = "multi-asset",
            tradeProbability = 1.0,
            instrumentIds = listOf("UST-5Y"),
            notionalRangeUsd = 100_000L..200_000L,
            assetClass = "EQUITY",
        )

        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(book),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = fixedClock(atUtc("2026-05-18", "12:00:00")),
            random = Random(seed = 11),
        )

        runTest { job.runTick() }

        captured.isNotEmpty() shouldBe true
        captured.forEach { request ->
            request.instrumentId shouldBe "UST-5Y"
            request.assetClass shouldBe "FIXED_INCOME"
            request.instrumentType shouldBe "GOVERNMENT_BOND"
        }
    }

    test("AAPL stays EQUITY / CASH_EQUITY in an EQUITY-tagged book (regression guard)") {
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<StrategyTradeRequest>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += thirdArg<StrategyTradeRequest>()
            "trade-${captured.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val book = DemoBookProfile(
            bookId = "equity-growth",
            tradeProbability = 1.0,
            instrumentIds = listOf("AAPL"),
            notionalRangeUsd = 50_000L..100_000L,
            assetClass = "EQUITY",
        )

        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(book),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = fixedClock(atUtc("2026-05-18", "12:00:00")),
            random = Random(seed = 7),
        )

        runTest { job.runTick() }

        captured.isNotEmpty() shouldBe true
        captured.forEach { request ->
            request.instrumentId shouldBe "AAPL"
            request.assetClass shouldBe "EQUITY"
            request.instrumentType shouldBe "CASH_EQUITY"
        }
    }

    test("PG stays EQUITY / CASH_EQUITY — Procter & Gamble is a real cash equity, not a Treasury despite sitting next to UST-* in the balanced-income book") {
        val client = mockk<PositionServiceClient>()
        val captured = mutableListOf<StrategyTradeRequest>()
        coEvery { client.bookTrade(any(), any(), any()) } answers {
            captured += thirdArg<StrategyTradeRequest>()
            "trade-${captured.size}"
        }
        coEvery { client.recordExecutionCost(any(), any()) } returns Unit
        coEvery { client.uploadPrimeBrokerStatement(any(), any()) } returns Unit

        val book = DemoBookProfile(
            bookId = "balanced-income",
            tradeProbability = 1.0,
            instrumentIds = listOf("PG"),
            notionalRangeUsd = 50_000L..100_000L,
            assetClass = "EQUITY",
        )

        val job = SimulatedTraderJob(
            positionClient = client,
            strategyIdResolver = stubResolver(),
            books = listOf(book),
            tradingHoursStart = tradingHoursStart,
            tradingHoursEnd = tradingHoursEnd,
            clock = fixedClock(atUtc("2026-05-18", "12:00:00")),
            random = Random(seed = 7),
        )

        runTest { job.runTick() }

        captured.isNotEmpty() shouldBe true
        captured.forEach { request ->
            request.instrumentId shouldBe "PG"
            request.assetClass shouldBe "EQUITY"
            request.instrumentType shouldBe "CASH_EQUITY"
        }
    }
})
