package com.kinetix.risk.seed

import com.kinetix.common.demo.RegimeCalendar
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/**
 * Unit tests for [PnLAttributionDeriver] — covers the math invariants on the
 * derived attributions: tie-out, coverage, options-book Greek population, and
 * the stress-window drawdown signature. The Testcontainers-backed acceptance
 * test [PnLAttributionAcceptanceTest] exercises the same logic end-to-end
 * through the real Postgres persistence path.
 */
class PnLAttributionDeriverTest : FunSpec({

    val deriver = PnLAttributionDeriver()
    val attributions = deriver.derive()

    test("derives attributions for every book in DemoBookCatalogue") {
        attributions.shouldNotBeEmpty()
        attributions.map { it.bookId.value }.toSet() shouldBe DemoBookCatalogue.BOOKS.keys
    }

    test("every book has exactly 251 daily rows (252-day tape minus one for d/d+1)") {
        val expectedRows = RegimeCalendar.DAYS - 1
        DemoBookCatalogue.BOOKS.keys.forEach { bookId ->
            val rows = attributions.filter { it.bookId.value == bookId }
            rows shouldHaveSize expectedRows
        }
    }

    test("every row's total ties out: total = delta + gamma + vega + theta + rho + crossGreeks + unexplained") {
        attributions.forEach { row ->
            val sumOfParts = row.deltaPnl
                .add(row.gammaPnl).add(row.vegaPnl).add(row.thetaPnl).add(row.rhoPnl)
                .add(row.vannaPnl).add(row.volgaPnl).add(row.charmPnl).add(row.crossGammaPnl)
                .add(row.unexplainedPnl)
            row.totalPnl.compareTo(sumOfParts) shouldBe 0
        }
    }

    test("every position attribution ties out per row") {
        attributions.flatMap { it.positionAttributions }.forEach { p ->
            val sumOfParts = p.deltaPnl
                .add(p.gammaPnl).add(p.vegaPnl).add(p.thetaPnl).add(p.rhoPnl)
                .add(p.vannaPnl).add(p.volgaPnl).add(p.charmPnl).add(p.crossGammaPnl)
                .add(p.unexplainedPnl)
            p.totalPnl.compareTo(sumOfParts) shouldBe 0
        }
    }

    test("book total equals sum of position totals") {
        attributions.forEach { row ->
            val summed = row.positionAttributions
                .map { it.totalPnl }
                .fold(BigDecimal.ZERO.setScale(8)) { acc, v -> acc.add(v) }
            row.totalPnl.compareTo(summed) shouldBe 0
        }
    }

    test("options books carry non-zero gamma, vega, theta on at least one day") {
        val booksWithOptions = DemoBookCatalogue.BOOKS
            .filterValues { positions -> positions.any { it.isOption } }
            .keys

        booksWithOptions.shouldNotBeEmpty()

        booksWithOptions.forEach { bookId ->
            val rowsForBook = attributions.filter { it.bookId.value == bookId }
            val anyGamma = rowsForBook.any { it.gammaPnl.signum() != 0 }
            val anyVega = rowsForBook.any { it.vegaPnl.signum() != 0 }
            val anyTheta = rowsForBook.any { it.thetaPnl.signum() != 0 }
            check(anyGamma) { "Book '$bookId' has no non-zero gamma attribution in any of ${rowsForBook.size} rows" }
            check(anyVega) { "Book '$bookId' has no non-zero vega attribution in any of ${rowsForBook.size} rows" }
            check(anyTheta) { "Book '$bookId' has no non-zero theta attribution in any of ${rowsForBook.size} rows" }
        }
    }

    test("stress-window drawdown is visible — min daily P&L during stress is worse than min during calm") {
        val tape = PnLAttributionDeriver().let { com.kinetix.common.demo.DemoTape() }
        val stressWindows = tape.stressWindows()
        stressWindows.shouldNotBeEmpty()

        // Look at the equity-growth book — a long-only equity book is the cleanest
        // demo of stress-window drawdown because there's nothing to hedge the move.
        val equityRows = attributions.filter { it.bookId.value == "equity-growth" }
        val (stressRows, calmRows) = equityRows.partition { row ->
            stressWindows.any { window -> !row.date.isBefore(window.start) && !row.date.isAfter(window.end) }
        }
        stressRows.shouldNotBeEmpty()
        calmRows.shouldNotBeEmpty()

        val worstStress = stressRows.minOf { it.totalPnl.toDouble() }
        val worstCalm = calmRows.minOf { it.totalPnl.toDouble() }

        // The worst stress-window day must be materially worse than the worst calm
        // day. Use a 1.5x multiplier so the assertion can absorb GARCH noise without
        // turning into a tautology. Negative numbers => "worst" means most negative.
        check(worstStress < worstCalm * 1.5) {
            "Expected worst stress day P&L ($worstStress) to be at least 1.5x worse than worst calm day ($worstCalm) " +
                "for equity-growth book"
        }
    }

    test("deriver is deterministic — same seed produces byte-identical totals") {
        val first = PnLAttributionDeriver().derive().map { it.totalPnl.toPlainString() }
        val second = PnLAttributionDeriver().derive().map { it.totalPnl.toPlainString() }
        first shouldBe second
    }

    test("dataQualityFlag is FULL_ATTRIBUTION for option-bearing books and PRICE_ONLY for pure cash books") {
        val byBook = attributions.groupBy { it.bookId.value }
        DemoBookCatalogue.BOOKS.forEach { (bookId, positions) ->
            val hasOptions = positions.any { it.isOption }
            val flags = byBook[bookId]!!.map { it.dataQualityFlag.name }.toSet()
            val expected = if (hasOptions) "FULL_ATTRIBUTION" else "PRICE_ONLY"
            flags shouldBe setOf(expected)
        }
    }
})
