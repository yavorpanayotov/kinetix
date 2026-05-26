package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.profile.DemoBookProfiles
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [StressScenarioSeedJob].
 *
 * The job hits `POST /api/v1/risk/stress/{bookId}/canned/{scenarioName}` for
 * every canonical demo book so the Risk overview tile is populated immediately
 * after bootstrap and again at SOD.
 */
class StressScenarioSeedJobTest : FunSpec({

    val allBooks = DemoBookProfiles.all()

    test("fires +100BPS_PARALLEL canned scenario for every demo book") {
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.runCannedStressScenario(any(), any()) } just Runs

        runTest {
            StressScenarioSeedJob(client = client).runOnce()
        }

        coVerify(exactly = allBooks.size) {
            client.runCannedStressScenario(any(), "+100BPS_PARALLEL")
        }
    }

    test("sweeps every book in DemoBookProfiles.all()") {
        val seededBooks = mutableListOf<String>()
        val client = mockk<RiskOrchestratorClient>()
        coEvery {
            client.runCannedStressScenario(any(), any())
        } answers {
            seededBooks += firstArg<String>()
        }

        runTest {
            StressScenarioSeedJob(client = client).runOnce()
        }

        seededBooks shouldContainExactlyInAnyOrder allBooks.map { it.bookId }
    }

    test("isolates per-book failures so one broken book never aborts the sweep") {
        val failingBook = allBooks.first().bookId
        val client = mockk<RiskOrchestratorClient>()
        coEvery {
            client.runCannedStressScenario(failingBook, any())
        } throws RuntimeException("upstream down for $failingBook")
        allBooks.drop(1).forEach { profile ->
            coEvery {
                client.runCannedStressScenario(profile.bookId, any())
            } just Runs
        }

        runTest {
            StressScenarioSeedJob(client = client).runOnce()
        }

        coVerify(exactly = allBooks.size - 1) {
            client.runCannedStressScenario(
                match { it != failingBook },
                "+100BPS_PARALLEL",
            )
        }
    }

    test("uses the canonical 8 demo books when no override is supplied") {
        allBooks.size shouldBe 8
    }

    test("custom scenarioName overrides the default") {
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.runCannedStressScenario(any(), any()) } just Runs

        runTest {
            StressScenarioSeedJob(
                client = client,
                scenarioName = "SOME_OTHER_SCENARIO",
            ).runOnce()
        }

        coVerify(exactly = allBooks.size) {
            client.runCannedStressScenario(any(), "SOME_OTHER_SCENARIO")
        }
    }
})
