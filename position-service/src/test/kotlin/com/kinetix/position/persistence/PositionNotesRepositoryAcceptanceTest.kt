package com.kinetix.position.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

class PositionNotesRepositoryAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: PositionNotesRepository = ExposedPositionNotesRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) { PositionNotesTable.deleteAll() }
    }

    test("create persists a note that list then returns") {
        val created = repository.create(
            bookId = "BOOK-EQ-US",
            instrumentId = "AAPL",
            note = "Watching earnings call",
            author = "alice",
        )

        created.id shouldNotBe UUID(0L, 0L)
        created.bookId shouldBe "BOOK-EQ-US"
        created.instrumentId shouldBe "AAPL"
        created.note shouldBe "Watching earnings call"
        created.author shouldBe "alice"

        val listed = repository.list("BOOK-EQ-US")
        listed shouldHaveSize 1
        listed.single().id shouldBe created.id
        listed.single().note shouldBe "Watching earnings call"
    }

    test("list filters by instrumentId when supplied") {
        repository.create("BOOK-EQ-US", "AAPL", "apple note", "alice")
        repository.create("BOOK-EQ-US", "MSFT", "microsoft note", "alice")
        repository.create("BOOK-EQ-US", "AAPL", "second apple note", "bob")

        val applOnly = repository.list("BOOK-EQ-US", instrumentId = "AAPL")
        applOnly shouldHaveSize 2
        applOnly.forEach { it.instrumentId shouldBe "AAPL" }

        val msftOnly = repository.list("BOOK-EQ-US", instrumentId = "MSFT")
        msftOnly shouldHaveSize 1
        msftOnly.single().note shouldBe "microsoft note"
    }

    test("list returns notes newest first") {
        val first = repository.create("BOOK-EQ-US", "AAPL", "first", "alice")
        // The created_at column is a TIMESTAMPTZ — under heavy parallelism two
        // inserts can land in the same microsecond. Sleep enough to guarantee
        // strictly-monotonic timestamps.
        delay(10)
        val second = repository.create("BOOK-EQ-US", "AAPL", "second", "alice")
        delay(10)
        val third = repository.create("BOOK-EQ-US", "AAPL", "third", "alice")

        val listed = repository.list("BOOK-EQ-US")
        listed.map { it.id } shouldBe listOf(third.id, second.id, first.id)
    }

    test("deleteById removes the matching row and returns true") {
        val note = repository.create("BOOK-EQ-US", "AAPL", "to be deleted", "alice")

        repository.deleteById(note.id) shouldBe true
        repository.list("BOOK-EQ-US").shouldBeEmpty()
    }

    test("deleteById returns false when no row matches") {
        repository.create("BOOK-EQ-US", "AAPL", "stays", "alice")

        repository.deleteById(UUID.randomUUID()) shouldBe false
        repository.list("BOOK-EQ-US") shouldHaveSize 1
    }

    test("list returns empty list for an unknown bookId") {
        repository.create("BOOK-EQ-US", "AAPL", "apple note", "alice")

        repository.list("BOOK-DOES-NOT-EXIST").shouldBeEmpty()
    }

    test("list returns empty list for an empty bookId") {
        repository.create("BOOK-EQ-US", "AAPL", "apple note", "alice")

        repository.list("").shouldBeEmpty()
    }

    test("list scopes to the requested book and excludes other books' notes") {
        repository.create("BOOK-EQ-US", "AAPL", "us note", "alice")
        repository.create("BOOK-EQ-EU", "AAPL", "eu note", "alice")

        val usNotes = repository.list("BOOK-EQ-US")
        usNotes shouldHaveSize 1
        usNotes.single().note shouldBe "us note"
    }
})
