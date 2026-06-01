package com.kinetix.demo.schedule

import com.kinetix.demo.client.RegulatoryServiceClient
import com.kinetix.demo.profile.DemoBookProfiles
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [FrtbSeedJob] (issue kx-kzbs).
 *
 * The job fires `POST /api/v1/regulatory/frtb/{bookId}/calculate` for every
 * canonical demo book so that `GET …/frtb/{bookId}/latest` returns 200 (a
 * persisted "latest" record) instead of 404 on a fresh demo.
 */
class FrtbSeedJobTest : FunSpec({

    val allBooks = DemoBookProfiles.all()

    test("triggers an FRTB calculation for every demo book so latest is no longer 404 (kx-kzbs)") {
        val triggered = mutableListOf<String>()
        val client = mockk<RegulatoryServiceClient>()
        coEvery { client.calculateFrtb(any()) } answers {
            triggered += firstArg<String>()
        }

        runTest {
            FrtbSeedJob(client = client).runOnce()
        }

        coVerify(exactly = allBooks.size) { client.calculateFrtb(any()) }
        triggered shouldContainExactlyInAnyOrder allBooks.map { it.bookId }
    }

    test("isolates per-book failures so one broken book never aborts the sweep (kx-kzbs)") {
        val failingBook = allBooks.first().bookId
        val client = mockk<RegulatoryServiceClient>()
        coEvery { client.calculateFrtb(failingBook) } throws
            RuntimeException("regulatory FRTB 500 for $failingBook")
        allBooks.drop(1).forEach { profile ->
            coEvery { client.calculateFrtb(profile.bookId) } just Runs
        }

        runTest {
            FrtbSeedJob(client = client).runOnce()
        }

        // Every other book is still attempted despite the one failing book.
        coVerify(exactly = allBooks.size) { client.calculateFrtb(any()) }
    }

    test("sweeps a custom set of books when supplied") {
        val client = mockk<RegulatoryServiceClient>()
        coEvery { client.calculateFrtb(any()) } just Runs
        val books = allBooks.take(2)

        runTest {
            FrtbSeedJob(client = client, books = books).runOnce()
        }

        coVerify(exactly = 2) { client.calculateFrtb(any()) }
    }
})
