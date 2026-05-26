package com.kinetix.demo.schedule

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class CounterpartyRotationTest : FunSpec({

    test("default pool is the 6 canonical demo counterparties (kx-i72)") {
        val rotation = CounterpartyRotation()
        rotation.pool shouldBe listOf("CP-GS", "CP-JPM", "CP-BARC", "CP-DB", "CP-UBS", "CP-CITI")
    }

    test("cycles through the pool in order for a single book") {
        val rotation = CounterpartyRotation(listOf("A", "B", "C"))

        val seq = (1..7).map { rotation.next("book-1") }

        seq shouldContainExactly listOf("A", "B", "C", "A", "B", "C", "A")
    }

    test("each book has its own independent cursor") {
        val rotation = CounterpartyRotation(listOf("A", "B", "C"))

        rotation.next("book-1") shouldBe "A"
        rotation.next("book-2") shouldBe "A"
        rotation.next("book-1") shouldBe "B"
        rotation.next("book-2") shouldBe "B"
        rotation.next("book-3") shouldBe "A" // new book starts fresh
    }

    test("over many ticks across multiple books, every book sees every counterparty (kx-i72 acceptance)") {
        val rotation = CounterpartyRotation()
        val pool = rotation.pool
        val books = listOf("book-1", "book-2", "book-3")

        val seenByBook = books.associateWith { mutableSetOf<String>() }
        // 100 calls per book is comfortably more than pool.size — even with one
        // call per tick a book hits every CP within `pool.size` calls.
        repeat(100) {
            for (book in books) {
                seenByBook.getValue(book).add(rotation.next(book))
            }
        }

        for (book in books) {
            seenByBook.getValue(book) shouldBe pool.toSet()
        }
    }

    test("empty pool is rejected") {
        shouldThrow<IllegalArgumentException> {
            CounterpartyRotation(emptyList())
        }
    }
})
