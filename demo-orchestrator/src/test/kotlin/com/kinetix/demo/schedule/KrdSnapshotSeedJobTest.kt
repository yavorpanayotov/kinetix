package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.profile.DemoBookProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [KrdSnapshotSeedJob] (issue kx-l8s7).
 *
 * The job triggers a KRD computation for the rate-oriented demo books — the
 * ones that actually hold government-bond positions — so the KRD endpoint
 * returns populated data instead of empty instruments/aggregated.
 */
class KrdSnapshotSeedJobTest : FunSpec({

    test("triggers a KRD snapshot only for books that hold government bonds (kx-l8s7)") {
        val triggered = mutableListOf<String>()
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.triggerKrdSnapshot(any()) } answers {
            triggered += firstArg<String>()
        }

        runTest {
            KrdSnapshotSeedJob(client = client).runOnce()
        }

        // Every demo book that trades a UST treasury gets a KRD trigger:
        // fixed-income (UST-2Y/5Y/10Y/30Y), multi-asset (UST-5Y/10Y),
        // macro-hedge (UST-10Y) and balanced-income (UST-5Y/10Y). The pure
        // equity / derivative books (no bonds) are skipped.
        triggered shouldContainExactlyInAnyOrder
            listOf("fixed-income", "multi-asset", "macro-hedge", "balanced-income")
    }

    test("skips books with no bond instruments (kx-l8s7)") {
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.triggerKrdSnapshot(any()) } just Runs
        val equityOnly = listOf(
            DemoBookProfile(
                bookId = "equity-only",
                tradeProbability = 1.0,
                instrumentIds = listOf("AAPL", "MSFT"),
                notionalRangeUsd = 1L..2L,
                assetClass = "EQUITY",
            ),
        )

        runTest {
            KrdSnapshotSeedJob(client = client, books = equityOnly).runOnce()
        }

        coVerify(exactly = 0) { client.triggerKrdSnapshot(any()) }
    }

    test("isolates per-book failures so one broken book never aborts the sweep (kx-l8s7)") {
        val client = mockk<RiskOrchestratorClient>()
        val rateBooks = listOf(
            DemoBookProfile(
                bookId = "rates-a",
                tradeProbability = 1.0,
                instrumentIds = listOf("UST-10Y"),
                notionalRangeUsd = 1L..2L,
                assetClass = "FIXED_INCOME",
            ),
            DemoBookProfile(
                bookId = "rates-b",
                tradeProbability = 1.0,
                instrumentIds = listOf("UST-5Y"),
                notionalRangeUsd = 1L..2L,
                assetClass = "FIXED_INCOME",
            ),
        )
        coEvery { client.triggerKrdSnapshot("rates-a") } throws RuntimeException("krd 500")
        coEvery { client.triggerKrdSnapshot("rates-b") } just Runs

        runTest {
            KrdSnapshotSeedJob(client = client, books = rateBooks).runOnce()
        }

        coVerify(exactly = 1) { client.triggerKrdSnapshot("rates-a") }
        coVerify(exactly = 1) { client.triggerKrdSnapshot("rates-b") }
    }
})
