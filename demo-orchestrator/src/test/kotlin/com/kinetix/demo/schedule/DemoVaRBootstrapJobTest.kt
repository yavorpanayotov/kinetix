package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for [DemoVaRBootstrapJob].
 *
 * The job fires once on startup and calculates VaR for every demo book so
 * the Risk / P&L / EOD / Reports tabs show real values rather than $0.00.
 * Tests exercise the full sweep, idempotency, failure isolation, empty-positions
 * terminal classification, ConnectException retry exhaustion, and transient-5xx
 * recovery.
 */
class DemoVaRBootstrapJobTest : FunSpec({

    val bookA = "balanced-income"
    val bookB = "derivatives-book"
    val bookC = "emerging-markets"
    val bookD = "equity-growth"
    val bookE = "fixed-income"
    val bookF = "macro-hedge"
    val bookG = "multi-asset"
    val bookH = "tech-momentum"

    val allBooks = setOf(bookA, bookB, bookC, bookD, bookE, bookF, bookG, bookH)

    val fixedDate = LocalDate.of(2026, 5, 26)
    val fixedClock = Clock.fixed(
        fixedDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
        ZoneOffset.UTC,
    )

    fun happyClient(): RiskOrchestratorClient {
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.calculateVaRWithParams(any(), any(), any(), any(), any()) } returns Unit
        coEvery { client.calculateCrossBookVaR(any(), any(), any(), any(), any()) } returns Unit
        return client
    }

    // ──────────────────────────────────────────────────────────────────────
    // (a) calls VaR for every seeded book exactly once on first run
    // ──────────────────────────────────────────────────────────────────────

    test("calls VaR for every seeded book exactly once on first run") {
        val client = happyClient()
        val job = DemoVaRBootstrapJob(
            riskOrchestratorClient = client,
            bookProvider = { allBooks },
            clock = fixedClock,
        )

        var result: BootstrapResult? = null
        runTest { result = job.runOnce() }

        result!!.successCount shouldBe 8
        result!!.failureCount shouldBe 0
        result!!.failedBooks shouldBe emptyList()

        for (bookId in allBooks) {
            coVerify(exactly = 1) {
                client.calculateVaRWithParams(bookId, any(), any(), any(), any())
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // (b) second runOnce() completes cleanly — idempotency
    // ──────────────────────────────────────────────────────────────────────

    test("second runOnce() completes cleanly with same successCount (idempotency)") {
        val client = happyClient()
        val job = DemoVaRBootstrapJob(
            riskOrchestratorClient = client,
            bookProvider = { allBooks },
            clock = fixedClock,
        )

        var firstResult: BootstrapResult? = null
        var secondResult: BootstrapResult? = null
        runTest {
            firstResult = job.runOnce()
            secondResult = job.runOnce()
        }

        firstResult!!.successCount shouldBe 8
        secondResult!!.successCount shouldBe 8
        secondResult!!.failureCount shouldBe 0
        secondResult!!.failedBooks shouldBe emptyList()
    }

    // ──────────────────────────────────────────────────────────────────────
    // (c) one book throws — continues and records failure
    // ──────────────────────────────────────────────────────────────────────

    test("continues after one book throws, records failure in failedBooks") {
        val client = happyClient()
        coEvery {
            client.calculateVaRWithParams(bookA, any(), any(), any(), any())
        } throws RuntimeException("pricing service unavailable")

        val job = DemoVaRBootstrapJob(
            riskOrchestratorClient = client,
            bookProvider = { allBooks },
            clock = fixedClock,
        )

        var result: BootstrapResult? = null
        runTest { result = job.runOnce() }

        result!!.successCount shouldBe 7
        result!!.failureCount shouldBe 1
        result!!.failedBooks shouldContain bookA
        result!!.failedBooks.size shouldBe 1

        // Every other book must have been called
        for (bookId in allBooks - bookA) {
            coVerify(exactly = 1) {
                client.calculateVaRWithParams(bookId, any(), any(), any(), any())
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // (d) empty-positions HTTP 400 is classified EMPTY and not retried
    // ──────────────────────────────────────────────────────────────────────

    test("empty-positions failure is classified EMPTY and not retried") {
        val client = happyClient()
        val emptyPositionsException = IllegalStateException(
            "risk-orchestrator POST /api/v1/risk/var/$bookB returned 400: " +
                "Cannot calculate VaR on empty positions list",
        )
        coEvery {
            client.calculateVaRWithParams(bookB, any(), any(), any(), any())
        } throws emptyPositionsException

        val job = DemoVaRBootstrapJob(
            riskOrchestratorClient = client,
            bookProvider = { setOf(bookA, bookB) },
            clock = fixedClock,
        )

        var result: BootstrapResult? = null
        runTest { result = job.runOnce() }

        result!!.failedBooks shouldContain bookB
        // Exactly 1 attempt — no retry on empty positions
        coVerify(exactly = 1) {
            client.calculateVaRWithParams(bookB, any(), any(), any(), any())
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // (e) ConnectException exhausts 3 retries then records failure
    // ──────────────────────────────────────────────────────────────────────

    test("retries up to 3 times on ConnectException then gives up") {
        val client = happyClient()
        coEvery {
            client.calculateVaRWithParams(bookC, any(), any(), any(), any())
        } throws ConnectException("Connection refused")

        val job = DemoVaRBootstrapJob(
            riskOrchestratorClient = client,
            bookProvider = { setOf(bookC) },
            clock = fixedClock,
            retryDelayMillis = 0L,    // eliminate delays in tests
        )

        var result: BootstrapResult? = null
        runTest { result = job.runOnce() }

        result!!.failureCount shouldBe 1
        result!!.failedBooks shouldContain bookC

        // 3 attempts before giving up
        coVerify(exactly = 3) {
            client.calculateVaRWithParams(bookC, any(), any(), any(), any())
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // (f) transient 5xx recovers on third attempt — recorded as success
    // ──────────────────────────────────────────────────────────────────────

    test("calls calculateCrossBookVaR with all successful books and portfolioGroupId=firm after per-book sweep") {
        // Phase 3.4 regression fix: per-book VaR alone is insufficient to populate
        // GET /api/v1/risk/var/cross-book/firm — an explicit cross-book POST is required.
        val client = happyClient()
        val job = DemoVaRBootstrapJob(
            riskOrchestratorClient = client,
            bookProvider = { allBooks },
            clock = fixedClock,
        )

        runTest { job.runOnce() }

        coVerify(exactly = 1) {
            client.calculateCrossBookVaR(
                bookIds = match { it.size == 8 && it.containsAll(allBooks) },
                portfolioGroupId = "firm",
                any(),
                any(),
                any(),
            )
        }
    }

    test("cross-book aggregate includes only books whose per-book VaR succeeded") {
        // If some books fail, the firm aggregate should be seeded with only the
        // succeeded books rather than the full set — a partial aggregate is better
        // than no aggregate at all.
        val client = happyClient()
        coEvery {
            client.calculateVaRWithParams(bookA, any(), any(), any(), any())
        } throws RuntimeException("pricing unavailable")

        val job = DemoVaRBootstrapJob(
            riskOrchestratorClient = client,
            bookProvider = { allBooks },
            clock = fixedClock,
        )

        runTest { job.runOnce() }

        coVerify(exactly = 1) {
            client.calculateCrossBookVaR(
                bookIds = match { !it.contains(bookA) && it.size == 7 },
                portfolioGroupId = "firm",
                any(),
                any(),
                any(),
            )
        }
    }

    test("skips cross-book aggregate call when all per-book VaR calculations fail") {
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.calculateVaRWithParams(any(), any(), any(), any(), any()) } throws
            RuntimeException("pricing unavailable")

        val job = DemoVaRBootstrapJob(
            riskOrchestratorClient = client,
            bookProvider = { setOf(bookA, bookB) },
            clock = fixedClock,
            retryDelayMillis = 0L,
        )

        runTest { job.runOnce() }

        // No succeeded books — cross-book aggregate must not be called
        coVerify(exactly = 0) {
            client.calculateCrossBookVaR(any(), any(), any(), any(), any())
        }
    }

    test("retries transient 5xx and eventually succeeds") {
        val client = happyClient()
        var callCount = 0
        coEvery {
            client.calculateVaRWithParams(bookD, any(), any(), any(), any())
        } answers {
            callCount++
            if (callCount < 3) {
                throw IllegalStateException("risk-orchestrator POST returned 503: service unavailable")
            }
        }

        val job = DemoVaRBootstrapJob(
            riskOrchestratorClient = client,
            bookProvider = { setOf(bookD) },
            clock = fixedClock,
            retryDelayMillis = 0L,
        )

        var result: BootstrapResult? = null
        runTest { result = job.runOnce() }

        result!!.successCount shouldBe 1
        result!!.failureCount shouldBe 0
        result!!.failedBooks shouldNotContain bookD

        coVerify(exactly = 3) {
            client.calculateVaRWithParams(bookD, any(), any(), any(), any())
        }
    }
})
