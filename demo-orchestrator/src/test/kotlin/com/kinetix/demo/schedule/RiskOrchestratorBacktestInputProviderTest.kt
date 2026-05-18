package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.client.dtos.BacktestRequest
import com.kinetix.demo.client.dtos.EodTimelineEntryDto
import com.kinetix.demo.client.dtos.EodTimelineResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for [RiskOrchestratorBacktestInputProvider].
 *
 * The provider pulls EOD entries from risk-orchestrator and pairs consecutive
 * `(varValue, pvValue)` snapshots into `(prediction, realised P&L)` samples.
 * These tests pin down the pairing math, the null-skip behaviour, the
 * fallback-to-stub thresholds, and the window calculation.
 */
class RiskOrchestratorBacktestInputProviderTest : FunSpec({

    val fixedToday: LocalDate = LocalDate.parse("2026-05-18")
    val fixedClock: Clock = Clock.fixed(
        Instant.parse("2026-05-18T00:00:00Z"),
        ZoneOffset.UTC,
    )

    fun entry(date: String, varValue: Double?, pvValue: Double?): EodTimelineEntryDto =
        EodTimelineEntryDto(valuationDate = date, varValue = varValue, pvValue = pvValue)

    fun response(entries: List<EodTimelineEntryDto>): EodTimelineResponse =
        EodTimelineResponse(
            bookId = "alpha",
            from = "2026-04-17",
            to = "2026-05-18",
            entries = entries,
        )

    test("happy path returns paired samples with correct math for 10 dense entries") {
        val client = mockk<RiskOrchestratorClient>()
        val entries = (0 until 10).map { i ->
            entry(
                date = LocalDate.parse("2026-05-01").plusDays(i.toLong()).toString(),
                varValue = 1_000.0 + i * 10.0,
                pvValue = 50_000.0 + i * 100.0,
            )
        }
        coEvery { client.eodTimeline("alpha", any(), any()) } returns response(entries)

        val fallback = mockk<BacktestInputProvider>()
        val provider = RiskOrchestratorBacktestInputProvider(
            client = client,
            fallback = fallback,
            clock = fixedClock,
        )

        runTest {
            val request = provider.fetchFor("alpha")

            request.dailyVarPredictions shouldHaveSize 9
            request.dailyPnl shouldHaveSize 9
            request.confidenceLevel shouldBe 0.99
            request.calculationType shouldBe "PARAMETRIC"

            // prediction_t = e_{t-1}.varValue
            request.dailyVarPredictions[0] shouldBe 1_000.0
            request.dailyVarPredictions[8] shouldBe 1_080.0

            // pnl_t = e_t.pvValue - e_{t-1}.pvValue = 100.0 each step
            request.dailyPnl.forEach { pnl -> pnl shouldBe (100.0 plusOrMinus 1e-9) }

            coVerify(exactly = 0) { fallback.fetchFor(any()) }
        }
    }

    test("entries with null varValue or pvValue are skipped") {
        val client = mockk<RiskOrchestratorClient>()
        // 12 entries: pairs that involve null fields are dropped.
        val entries = listOf(
            entry("2026-05-01", varValue = 1_000.0, pvValue = 50_000.0),
            entry("2026-05-02", varValue = 1_010.0, pvValue = 50_100.0), // pair (0->1) valid: var=1000
            entry("2026-05-03", varValue = null, pvValue = 50_200.0),    // pair (1->2) valid: var=1010; (2->3) dropped (prev.var null)
            entry("2026-05-04", varValue = 1_030.0, pvValue = 50_300.0), // (2->3) dropped
            entry("2026-05-05", varValue = 1_040.0, pvValue = null),     // (3->4) dropped (cur.pv null)
            entry("2026-05-06", varValue = 1_050.0, pvValue = 50_500.0), // (4->5) dropped (prev.pv null)
            entry("2026-05-07", varValue = 1_060.0, pvValue = 50_600.0), // (5->6) valid: var=1050
            entry("2026-05-08", varValue = 1_070.0, pvValue = 50_700.0), // (6->7) valid: var=1060
            entry("2026-05-09", varValue = 1_080.0, pvValue = 50_800.0), // (7->8) valid: var=1070
            entry("2026-05-10", varValue = 1_090.0, pvValue = 50_900.0), // (8->9) valid: var=1080
            entry("2026-05-11", varValue = 1_100.0, pvValue = 51_000.0), // (9->10) valid: var=1090
            entry("2026-05-12", varValue = 1_110.0, pvValue = 51_100.0), // (10->11) valid: var=1100
        )
        coEvery { client.eodTimeline("alpha", any(), any()) } returns response(entries)

        val fallback = mockk<BacktestInputProvider>()
        val provider = RiskOrchestratorBacktestInputProvider(
            client = client,
            fallback = fallback,
            clock = fixedClock,
        )

        runTest {
            val request = provider.fetchFor("alpha")

            request.dailyVarPredictions shouldHaveSize 8
            request.dailyPnl shouldHaveSize 8
            request.dailyVarPredictions shouldBe listOf(
                1_000.0, 1_010.0, 1_050.0, 1_060.0, 1_070.0, 1_080.0, 1_090.0, 1_100.0,
            )
            request.dailyPnl.forEach { pnl -> pnl shouldBe (100.0 plusOrMinus 1e-9) }

            coVerify(exactly = 0) { fallback.fetchFor(any()) }
        }
    }

    test("falls back to stub when fewer than 5 valid paired samples") {
        val client = mockk<RiskOrchestratorClient>()
        // Only 3 dense entries → 2 valid pairs.
        val entries = listOf(
            entry("2026-05-01", varValue = 1_000.0, pvValue = 50_000.0),
            entry("2026-05-02", varValue = 1_010.0, pvValue = 50_100.0),
            entry("2026-05-03", varValue = 1_020.0, pvValue = 50_200.0),
        )
        coEvery { client.eodTimeline("alpha", any(), any()) } returns response(entries)

        val fallback = mockk<BacktestInputProvider>()
        val stubRequest = BacktestRequest(
            dailyVarPredictions = List(30) { 1_000.0 },
            dailyPnl = List(30) { 0.0 },
            confidenceLevel = 0.99,
            calculationType = "PARAMETRIC",
        )
        coEvery { fallback.fetchFor("alpha") } returns stubRequest

        val provider = RiskOrchestratorBacktestInputProvider(
            client = client,
            fallback = fallback,
            clock = fixedClock,
        )

        runTest {
            val request = provider.fetchFor("alpha")
            request shouldBe stubRequest
            coVerify(exactly = 1) { fallback.fetchFor("alpha") }
        }
    }

    test("falls back to stub when client.eodTimeline throws") {
        val client = mockk<RiskOrchestratorClient>()
        coEvery {
            client.eodTimeline("alpha", any(), any())
        } throws RuntimeException("orchestrator 500")

        val fallback = mockk<BacktestInputProvider>()
        val stubRequest = BacktestRequest(
            dailyVarPredictions = List(30) { 1_000.0 },
            dailyPnl = List(30) { 250.0 },
            confidenceLevel = 0.99,
            calculationType = "PARAMETRIC",
        )
        coEvery { fallback.fetchFor("alpha") } returns stubRequest

        val provider = RiskOrchestratorBacktestInputProvider(
            client = client,
            fallback = fallback,
            clock = fixedClock,
        )

        runTest {
            val request = provider.fetchFor("alpha")
            request shouldBe stubRequest
            coVerify(exactly = 1) { fallback.fetchFor("alpha") }
        }
    }

    test("window passes today and today-31 days to the client") {
        val client = mockk<RiskOrchestratorClient>()
        val fromSlot = slot<LocalDate>()
        val toSlot = slot<LocalDate>()
        val entries = (0 until 10).map { i ->
            entry(
                date = LocalDate.parse("2026-04-18").plusDays(i.toLong()).toString(),
                varValue = 1_000.0,
                pvValue = 50_000.0 + i * 100.0,
            )
        }
        coEvery {
            client.eodTimeline("alpha", capture(fromSlot), capture(toSlot))
        } returns response(entries)

        val provider = RiskOrchestratorBacktestInputProvider(
            client = client,
            fallback = StubBacktestInputProvider(),
            clock = fixedClock,
        )

        runTest {
            provider.fetchFor("alpha")
            toSlot.captured shouldBe fixedToday
            fromSlot.captured shouldBe fixedToday.minusDays(31)
            fromSlot.captured shouldBe LocalDate.parse("2026-04-17")
        }
    }
})
