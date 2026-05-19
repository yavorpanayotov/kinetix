package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.client.dtos.EodPromotionResponseDto
import com.kinetix.demo.client.dtos.ValuationJobSummary
import com.kinetix.demo.profile.DemoBookProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for [EodPromotionJob].
 *
 * The job orchestrates three upstream calls per book — VaR calculation,
 * latest-job lookup, and promotion — gated by an idempotency check
 * (`findOfficialEod`). The integration test in
 * `EodPromotionIntegrationTest` exercises the real HTTP wire; these tests
 * cover branching behaviour without spinning up a Ktor server.
 */
class EodPromotionJobTest : FunSpec({

    val balancedIncome = DemoBookProfile(
        bookId = "balanced-income",
        tradeProbability = 0.0,
        instrumentIds = listOf("JNJ"),
        notionalRangeUsd = 1L..2L,
        assetClass = "EQUITY",
    )

    val derivatives = DemoBookProfile(
        bookId = "derivatives-book",
        tradeProbability = 0.0,
        instrumentIds = listOf("SPX-OPT-5000C"),
        notionalRangeUsd = 1L..2L,
        assetClass = "DERIVATIVE",
    )

    val valuationDate = LocalDate.of(2026, 5, 18)
    val fixedClock = Clock.fixed(
        valuationDate.atTime(17, 0).atZone(ZoneOffset.UTC).toInstant(),
        ZoneOffset.UTC,
    )

    fun newClient(): RiskOrchestratorClient {
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.findOfficialEod(any(), any()) } returns null
        coEvery { client.calculateVaR(any()) } just Runs
        coEvery { client.findLatestCompletedJob(any()) } answers {
            ValuationJobSummary(
                jobId = "job-${firstArg<String>()}",
                bookId = firstArg(),
                status = "COMPLETED",
                valuationDate = valuationDate.toString(),
            )
        }
        coEvery { client.promoteJobToOfficialEod(any(), any()) } answers {
            EodPromotionResponseDto(
                jobId = firstArg(),
                bookId = "balanced-income",
                valuationDate = valuationDate.toString(),
                runLabel = "OFFICIAL_EOD",
                promotedAt = "2026-05-18T17:00:00Z",
                promotedBy = secondArg(),
            )
        }
        return client
    }

    test("happy path: every book is promoted exactly once") {
        val client = newClient()
        val job = EodPromotionJob(
            client = client,
            books = listOf(balancedIncome, derivatives),
            clock = fixedClock,
        )

        var promoted = -1
        runTest { promoted = job.runOnce() }
        promoted shouldBe 2

        coVerify(exactly = 1) { client.findOfficialEod("balanced-income", valuationDate) }
        coVerify(exactly = 1) { client.findOfficialEod("derivatives-book", valuationDate) }
        coVerify(exactly = 1) { client.calculateVaR("balanced-income") }
        coVerify(exactly = 1) { client.calculateVaR("derivatives-book") }
        coVerify(exactly = 1) {
            client.promoteJobToOfficialEod("job-balanced-income", "demo-orchestrator")
        }
        coVerify(exactly = 1) {
            client.promoteJobToOfficialEod("job-derivatives-book", "demo-orchestrator")
        }
    }

    test("books already designated for today are skipped (idempotent)") {
        val client = newClient()
        coEvery { client.findOfficialEod("balanced-income", valuationDate) } returns
            EodPromotionResponseDto(
                jobId = "pre-existing",
                bookId = "balanced-income",
                valuationDate = valuationDate.toString(),
                runLabel = "OFFICIAL_EOD",
                promotedAt = "2026-05-18T17:00:00Z",
                promotedBy = "earlier-run",
            )

        val job = EodPromotionJob(
            client = client,
            books = listOf(balancedIncome, derivatives),
            clock = fixedClock,
        )

        var promoted = -1
        runTest { promoted = job.runOnce() }
        promoted shouldBe 1

        coVerify(exactly = 0) { client.calculateVaR("balanced-income") }
        coVerify(exactly = 1) { client.calculateVaR("derivatives-book") }
    }

    test("VaR failure for one book does not abort the sweep") {
        val client = newClient()
        coEvery { client.calculateVaR("balanced-income") } throws RuntimeException("var down")

        val job = EodPromotionJob(
            client = client,
            books = listOf(balancedIncome, derivatives),
            clock = fixedClock,
        )

        var promoted = -1
        runTest { promoted = job.runOnce() }
        promoted shouldBe 1

        coVerify(exactly = 0) {
            client.promoteJobToOfficialEod("job-balanced-income", any())
        }
        coVerify(exactly = 1) {
            client.promoteJobToOfficialEod("job-derivatives-book", any())
        }
    }

    test("missing latest job after VaR call is skipped without aborting") {
        val client = newClient()
        coEvery { client.findLatestCompletedJob("balanced-income") } returns null

        val job = EodPromotionJob(
            client = client,
            books = listOf(balancedIncome, derivatives),
            clock = fixedClock,
        )

        var promoted = -1
        runTest { promoted = job.runOnce() }
        promoted shouldBe 1
    }

    test("promotion failure does not propagate out of runOnce") {
        val client = newClient()
        coEvery {
            client.promoteJobToOfficialEod("job-balanced-income", any())
        } throws RuntimeException("conflict")

        val job = EodPromotionJob(
            client = client,
            books = listOf(balancedIncome),
            clock = fixedClock,
        )

        var promoted = -1
        runTest { promoted = job.runOnce() }
        promoted shouldBe 0
    }

    test("idempotency check failure is treated as a skip") {
        val client = newClient()
        coEvery {
            client.findOfficialEod("balanced-income", valuationDate)
        } throws RuntimeException("HTTP 500")

        val job = EodPromotionJob(
            client = client,
            books = listOf(balancedIncome),
            clock = fixedClock,
        )

        var promoted = -1
        runTest { promoted = job.runOnce() }
        promoted shouldBe 0

        coVerify(exactly = 0) { client.calculateVaR(any()) }
    }
})
