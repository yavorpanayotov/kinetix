package com.kinetix.position.service

import com.kinetix.position.model.PositionNote
import com.kinetix.position.persistence.PositionNotesRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

class PositionNotesServiceTest : FunSpec({

    val repository = mockk<PositionNotesRepository>()
    val service = PositionNotesService(repository)

    test("create delegates to repository when all inputs are non-blank") {
        val expected = PositionNote(
            id = UUID.randomUUID(),
            bookId = "BOOK-1",
            instrumentId = "AAPL",
            note = "watch this",
            author = "alice",
            createdAt = Instant.now(),
        )
        coEvery { repository.create("BOOK-1", "AAPL", "watch this", "alice") } returns expected

        val result = service.create("BOOK-1", "AAPL", "watch this", "alice")

        result shouldBe expected
        coVerify(exactly = 1) { repository.create("BOOK-1", "AAPL", "watch this", "alice") }
    }

    test("create rejects blank bookId") {
        shouldThrow<IllegalArgumentException> {
            service.create("", "AAPL", "note", "alice")
        }
    }

    test("create rejects blank instrumentId") {
        shouldThrow<IllegalArgumentException> {
            service.create("BOOK-1", "  ", "note", "alice")
        }
    }

    test("create rejects blank note") {
        shouldThrow<IllegalArgumentException> {
            service.create("BOOK-1", "AAPL", "", "alice")
        }
    }

    test("create rejects blank author") {
        shouldThrow<IllegalArgumentException> {
            service.create("BOOK-1", "AAPL", "note", "")
        }
    }

    test("list delegates to repository without filter") {
        val notes = listOf(
            PositionNote(UUID.randomUUID(), "BOOK-1", "AAPL", "n1", "alice", Instant.now()),
        )
        coEvery { repository.list("BOOK-1", null) } returns notes

        service.list("BOOK-1") shouldBe notes
    }

    test("list passes instrumentId filter through") {
        val notes = listOf(
            PositionNote(UUID.randomUUID(), "BOOK-1", "AAPL", "n1", "alice", Instant.now()),
        )
        coEvery { repository.list("BOOK-1", "AAPL") } returns notes

        service.list("BOOK-1", "AAPL") shouldBe notes
    }

    test("delete delegates to repository and returns its boolean result") {
        val id = UUID.randomUUID()
        coEvery { repository.deleteById(id) } returns true

        service.delete(id) shouldBe true
    }

    test("delete returns false when repository reports no row deleted") {
        val id = UUID.randomUUID()
        coEvery { repository.deleteById(id) } returns false

        service.delete(id) shouldBe false
    }
})
