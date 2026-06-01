package com.kinetix.demo.schedule

import com.kinetix.common.demo.CounterpartyTiers
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

class CounterpartyRotationTest : FunSpec({

    test("default pool is the full demo counterparty universe (kx-6o89)") {
        val rotation = CounterpartyRotation()
        // Previously only the 6 G-SIBs received exposure; the long tail of
        // mid-tier banks / CCPs / buy-side / corporates rendered as $0.00.
        // The default pool is now the full reference-data universe so trades
        // distribute across all 30 counterparties.
        rotation.pool shouldContainExactlyInAnyOrder CounterpartyTiers.ALL_IDS
        rotation.pool.size shouldBe 30
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

    test("eligibility-aware next restricts cleared/listed instruments to banks + CCPs (kx-6o89)") {
        val rotation = CounterpartyRotation()
        // A listed derivative is eligible for universal banks + CCPs only —
        // never buy-side or corporates.
        val seen = (1..200).map { rotation.next("listed-book", "EQUITY_OPTION") }.toSet()
        val expected = (CounterpartyTiers.UNIVERSAL_BANK_IDS + CounterpartyTiers.CCP_IDS).toSet()
        seen shouldBe expected
    }

    test("eligibility-aware next restricts OTC instruments to banks + buy-side + corporates (kx-6o89)") {
        val rotation = CounterpartyRotation()
        val seen = (1..200).map { rotation.next("otc-book", "INTEREST_RATE_SWAP") }.toSet()
        val expected = (
            CounterpartyTiers.UNIVERSAL_BANK_IDS +
                CounterpartyTiers.BUY_SIDE_IDS +
                CounterpartyTiers.CORPORATE_IDS
        ).toSet()
        seen shouldBe expected
        // CCPs must never appear on OTC instruments.
        (seen intersect CounterpartyTiers.CCP_IDS.toSet()) shouldBe emptySet()
    }

    test("eligibility-aware next restricts cash/govt/fx-spot to banks only (kx-6o89)") {
        val rotation = CounterpartyRotation()
        val seen = (1..200).map { rotation.next("cash-book", "CASH_EQUITY") }.toSet()
        seen shouldBe CounterpartyTiers.UNIVERSAL_BANK_IDS.toSet()
    }

    test("across a realistic mix of instrument types, substantially all 30 counterparties get exposure (kx-6o89)") {
        val rotation = CounterpartyRotation()
        // A book that trades a representative spread of instrument types so the
        // eligibility filter lets every tier through over the run.
        val instrumentTypes = listOf(
            "CASH_EQUITY", // banks
            "GOVERNMENT_BOND", // banks
            "FX_SPOT", // banks
            "EQUITY_OPTION", // banks + CCPs
            "COMMODITY_FUTURE", // banks + CCPs
            "INTEREST_RATE_SWAP", // banks + buy-side + corporates
            "FX_FORWARD", // banks + buy-side + corporates
            "CORPORATE_BOND", // banks + buy-side + corporates
        )
        val seen = mutableSetOf<String>()
        repeat(50) {
            for (type in instrumentTypes) {
                seen.add(rotation.next("mixed-book", type))
            }
        }
        // Every counterparty in the universe should have received at least one
        // trade — no $0.00 long tail.
        seen.size shouldBeGreaterThanOrEqual 30
        seen shouldContainExactlyInAnyOrder CounterpartyTiers.ALL_IDS
    }

    test("eligibility-aware next rotates within the intersection of pool and eligible tier") {
        // A custom pool of two banks; CASH_EQUITY is banks-only, so the
        // eligible subset is exactly the pool.
        val rotation = CounterpartyRotation(listOf("CP-GS", "CP-JPM"))
        val seen = (1..10).map { rotation.next("b", "CASH_EQUITY") }.toSet()
        seen shouldBe setOf("CP-GS", "CP-JPM")
    }

    test("eligibility-aware next falls back to the full pool when the pool shares no id with the eligible tier") {
        // A custom pool of CCP ids only; an OTC instrument type is NOT eligible
        // for CCPs, so the intersection is empty — the rotation falls back to
        // the full pool rather than returning nothing.
        val rotation = CounterpartyRotation(listOf("CP-LCH", "CP-CME"))
        val seen = (1..10).map { rotation.next("b", "INTEREST_RATE_SWAP") }.toSet()
        seen shouldBe setOf("CP-LCH", "CP-CME")
    }
})
