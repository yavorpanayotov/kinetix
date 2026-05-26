package com.kinetix.demo.schedule

import com.kinetix.demo.client.LimitType
import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.client.dtos.BookExposureSnapshot
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
import java.math.BigDecimal

/**
 * Unit tests for [LimitSeedJob].
 *
 * The job iterates over [DemoBookProfiles.all] — the canonical 8 seeded books
 * — reads each book's exposure snapshot via [RiskOrchestratorClient], and
 * posts two limits per book: a `VAR_95` set to 80% of `varValue` and a
 * `DELTA_ABS` set to 70% of `absoluteDelta` (when present).
 */
class LimitSeedJobTest : FunSpec({

    val allBooks = DemoBookProfiles.all()

    test("seeds VAR_95 at 80% of varValue and DELTA_ABS at 70% of absoluteDelta for every book") {
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.readBookExposure(any()) } answers {
            BookExposureSnapshot(
                bookId = firstArg(),
                varValue = BigDecimal("1000"),
                absoluteDelta = BigDecimal("500"),
            )
        }
        coEvery { client.seedLimit(any(), any(), any()) } just Runs

        runTest {
            // Use a non-existent breach book so every real book gets the
            // standard 80%/70% factors — isolates the default-factor logic.
            LimitSeedJob(client = client, breachBook = "__no_such_book__").runOnce()
        }

        coVerify(exactly = allBooks.size) {
            client.seedLimit(any(), LimitType.VAR_95, BigDecimal("800.00"))
        }
        coVerify(exactly = allBooks.size) {
            client.seedLimit(any(), LimitType.DELTA_ABS, BigDecimal("350.00"))
        }
    }

    test("iterates over every book returned by DemoBookProfiles.all()") {
        val seededBooks = mutableListOf<String>()
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.readBookExposure(any()) } answers {
            BookExposureSnapshot(
                bookId = firstArg(),
                varValue = BigDecimal("1000"),
                absoluteDelta = BigDecimal("500"),
            )
        }
        coEvery {
            client.seedLimit(any(), LimitType.VAR_95, any())
        } answers {
            seededBooks += firstArg<String>()
        }
        coEvery {
            client.seedLimit(any(), LimitType.DELTA_ABS, any())
        } just Runs

        runTest {
            LimitSeedJob(client = client).runOnce()
        }

        seededBooks shouldContainExactlyInAnyOrder allBooks.map { it.bookId }
    }

    test("custom factors override the defaults") {
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.readBookExposure(any()) } answers {
            BookExposureSnapshot(
                bookId = firstArg(),
                varValue = BigDecimal("1000"),
                absoluteDelta = BigDecimal("500"),
            )
        }
        coEvery { client.seedLimit(any(), any(), any()) } just Runs

        runTest {
            LimitSeedJob(
                client = client,
                varFactor = BigDecimal("0.5"),
                deltaFactor = BigDecimal("0.5"),
            ).runOnce()
        }

        coVerify(exactly = allBooks.size) {
            client.seedLimit(any(), LimitType.VAR_95, BigDecimal("500.00"))
        }
        coVerify(exactly = allBooks.size) {
            client.seedLimit(any(), LimitType.DELTA_ABS, BigDecimal("250.00"))
        }
    }

    test("skips DELTA_ABS when absoluteDelta is null but still seeds VAR_95") {
        val singleBook = listOf(allBooks.first())
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.readBookExposure(singleBook.first().bookId) } returns
            BookExposureSnapshot(
                bookId = singleBook.first().bookId,
                varValue = BigDecimal("1000"),
                absoluteDelta = null,
            )
        coEvery { client.seedLimit(any(), any(), any()) } just Runs

        runTest {
            LimitSeedJob(client = client, books = singleBook).runOnce()
        }

        coVerify(exactly = 1) {
            client.seedLimit(singleBook.first().bookId, LimitType.VAR_95, BigDecimal("800.00"))
        }
        coVerify(exactly = 0) {
            client.seedLimit(any(), LimitType.DELTA_ABS, any())
        }
    }

    test("skips VAR_95 when varValue is zero") {
        val singleBook = listOf(allBooks.first())
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.readBookExposure(singleBook.first().bookId) } returns
            BookExposureSnapshot(
                bookId = singleBook.first().bookId,
                varValue = BigDecimal.ZERO,
                absoluteDelta = BigDecimal("500"),
            )
        coEvery { client.seedLimit(any(), any(), any()) } just Runs

        runTest {
            LimitSeedJob(client = client, books = singleBook).runOnce()
        }

        coVerify(exactly = 0) {
            client.seedLimit(any(), LimitType.VAR_95, any())
        }
        coVerify(exactly = 1) {
            client.seedLimit(singleBook.first().bookId, LimitType.DELTA_ABS, BigDecimal("350.00"))
        }
    }

    test("skips a book whose readBookExposure throws and continues with the rest") {
        val failingBook = allBooks.first()
        val remainingBooks = allBooks.drop(1)
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.readBookExposure(failingBook.bookId) } throws
            RuntimeException("upstream down for ${failingBook.bookId}")
        remainingBooks.forEach { profile ->
            coEvery { client.readBookExposure(profile.bookId) } returns
                BookExposureSnapshot(
                    bookId = profile.bookId,
                    varValue = BigDecimal("1000"),
                    absoluteDelta = BigDecimal("500"),
                )
        }
        coEvery { client.seedLimit(any(), any(), any()) } just Runs

        runTest {
            // Use a non-existent breach book so every real book gets the
            // standard 80%/70% factors — isolates the resilience behaviour.
            LimitSeedJob(client = client, breachBook = "__no_such_book__").runOnce()
        }

        coVerify(exactly = 0) {
            client.seedLimit(failingBook.bookId, any(), any())
        }
        coVerify(exactly = remainingBooks.size) {
            client.seedLimit(any(), LimitType.VAR_95, BigDecimal("800.00"))
        }
        coVerify(exactly = remainingBooks.size) {
            client.seedLimit(any(), LimitType.DELTA_ABS, BigDecimal("350.00"))
        }
    }

    test("logs and continues when a seedLimit call throws") {
        val firstBook = allBooks.first()
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.readBookExposure(any()) } answers {
            BookExposureSnapshot(
                bookId = firstArg(),
                varValue = BigDecimal("1000"),
                absoluteDelta = BigDecimal("500"),
            )
        }
        // First book's VAR_95 call blows up — every other call should still happen.
        coEvery {
            client.seedLimit(firstBook.bookId, LimitType.VAR_95, any())
        } throws RuntimeException("seed failed for ${firstBook.bookId}")
        coEvery {
            client.seedLimit(firstBook.bookId, LimitType.DELTA_ABS, any())
        } just Runs
        allBooks.drop(1).forEach { profile ->
            coEvery {
                client.seedLimit(profile.bookId, any(), any())
            } just Runs
        }

        runTest {
            // Use a non-existent breach book so every real book gets the
            // standard 80%/70% factors — isolates the resilience behaviour.
            LimitSeedJob(client = client, breachBook = "__no_such_book__").runOnce()
        }

        // Every book is still attempted for VAR_95 — including the one that threw.
        coVerify(exactly = allBooks.size) {
            client.seedLimit(any(), LimitType.VAR_95, BigDecimal("800.00"))
        }
        // DELTA_ABS still seeded for every book (including the one whose VAR_95 threw).
        coVerify(exactly = allBooks.size) {
            client.seedLimit(any(), LimitType.DELTA_ABS, BigDecimal("350.00"))
        }
    }

    test("uses the canonical 8 demo books when no override is supplied") {
        // Sanity check that DemoBookProfiles.all() really is the source.
        allBooks.size shouldBe 8
    }

    test("seeds limits at standard factor for non-breach books") {
        // Every book except the configured breach book should be seeded at the
        // standard 80% / 70% factors, even when a breach factor is configured.
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.readBookExposure(any()) } answers {
            BookExposureSnapshot(
                bookId = firstArg(),
                varValue = BigDecimal("1000"),
                absoluteDelta = BigDecimal("500"),
            )
        }
        coEvery { client.seedLimit(any(), any(), any()) } just Runs

        runTest {
            LimitSeedJob(
                client = client,
                breachBook = "derivatives-book",
                breachFactor = BigDecimal("0.50"),
            ).runOnce()
        }

        val nonBreachBooks = allBooks.filter { it.bookId != "derivatives-book" }
        nonBreachBooks.forEach { profile ->
            coVerify(exactly = 1) {
                client.seedLimit(profile.bookId, LimitType.VAR_95, BigDecimal("800.00"))
            }
            coVerify(exactly = 1) {
                client.seedLimit(profile.bookId, LimitType.DELTA_ABS, BigDecimal("350.00"))
            }
        }
    }

    test("seeds limits at breach factor for the configured breach book") {
        // The configured breach book should get VAR_95 and DELTA_ABS at the
        // tighter breach factor (here 0.50) so the demo trader trips it fast.
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.readBookExposure(any()) } answers {
            BookExposureSnapshot(
                bookId = firstArg(),
                varValue = BigDecimal("1000"),
                absoluteDelta = BigDecimal("500"),
            )
        }
        coEvery { client.seedLimit(any(), any(), any()) } just Runs

        runTest {
            LimitSeedJob(
                client = client,
                breachBook = "derivatives-book",
                breachFactor = BigDecimal("0.50"),
            ).runOnce()
        }

        coVerify(exactly = 1) {
            client.seedLimit("derivatives-book", LimitType.VAR_95, BigDecimal("500.00"))
        }
        coVerify(exactly = 1) {
            client.seedLimit("derivatives-book", LimitType.DELTA_ABS, BigDecimal("250.00"))
        }
    }

    test("respects env-var override for breach book id") {
        // When DEMO_BREACH_BOOK names a different book, the breach factor
        // should follow it and the previous default book should fall back to
        // the standard factors.
        val client = mockk<RiskOrchestratorClient>()
        coEvery { client.readBookExposure(any()) } answers {
            BookExposureSnapshot(
                bookId = firstArg(),
                varValue = BigDecimal("1000"),
                absoluteDelta = BigDecimal("500"),
            )
        }
        coEvery { client.seedLimit(any(), any(), any()) } just Runs

        runTest {
            LimitSeedJob(
                client = client,
                breachBook = "macro-hedge",
                breachFactor = BigDecimal("0.50"),
            ).runOnce()
        }

        // The overridden breach book gets the tighter factor.
        coVerify(exactly = 1) {
            client.seedLimit("macro-hedge", LimitType.VAR_95, BigDecimal("500.00"))
        }
        coVerify(exactly = 1) {
            client.seedLimit("macro-hedge", LimitType.DELTA_ABS, BigDecimal("250.00"))
        }
        // The previously-default derivatives-book is now a standard-factor book.
        coVerify(exactly = 1) {
            client.seedLimit("derivatives-book", LimitType.VAR_95, BigDecimal("800.00"))
        }
        coVerify(exactly = 1) {
            client.seedLimit("derivatives-book", LimitType.DELTA_ABS, BigDecimal("350.00"))
        }
    }
})
