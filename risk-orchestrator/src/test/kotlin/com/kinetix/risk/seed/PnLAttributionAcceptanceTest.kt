package com.kinetix.risk.seed

import com.kinetix.common.demo.DemoTape
import com.kinetix.common.demo.RegimeCalendar
import com.kinetix.common.model.BookId
import com.kinetix.risk.model.PnlAttribution
import com.kinetix.risk.persistence.DatabaseTestSetup
import com.kinetix.risk.persistence.ExposedPnlAttributionRepository
import com.kinetix.risk.persistence.PnlAttributionsTable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate

/**
 * Acceptance test for the seeder's positions × tape-moves P&L attribution derivation.
 *
 * Spins up Postgres via Testcontainers, runs the seeder, and reads the persisted rows
 * back through the real Exposed repository. Validates the demo-day-1 invariants:
 *   1. Coverage  — every book has a full 251-day attribution timeline.
 *   2. Stress    — daily drawdowns during the 2020/2022-analog stress windows are
 *                  materially worse than the calmest periods; the tape's regime
 *                  structure is faithfully transmitted into the demo P&L story.
 *   3. Greeks    — option-bearing books have non-zero gamma/vega/theta on at least
 *                  one row, proving the Black-Scholes path actually runs and isn't
 *                  silently emitting zeros.
 *   4. Tie-out   — for every persisted row, total = sum of components.
 */
class PnLAttributionAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repo = ExposedPnlAttributionRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) { PnlAttributionsTable.deleteAll() }
    }

    test("seeder persists a full 251-day attribution timeline for every book") {
        val deriver = PnLAttributionDeriver()
        deriver.derive().forEach { repo.save(it) }

        val expectedRows = RegimeCalendar.DAYS - 1
        DemoBookCatalogue.BOOKS.keys.forEach { bookId ->
            val rows = repo.findByBookId(
                BookId(bookId),
                fromDate = LocalDate.now().minusYears(2),
            )
            rows shouldHaveSize expectedRows
        }
    }

    test("stress-window drawdowns are materially worse than calm-window drawdowns") {
        val deriver = PnLAttributionDeriver()
        deriver.derive().forEach { repo.save(it) }

        val tape = DemoTape()
        val stressWindows = tape.stressWindows()
        stressWindows.shouldNotBeEmpty()

        // Pick equity-growth as the demonstration book: long-only equity has the
        // cleanest stress-window signal because there's no hedge cancelling out
        // the negative tape moves.
        val rows = repo.findByBookId(
            BookId("equity-growth"),
            fromDate = LocalDate.now().minusYears(2),
        )

        val (stressRows, calmRows) = partitionByWindows(rows, stressWindows)
        stressRows.shouldNotBeEmpty()
        calmRows.shouldNotBeEmpty()

        val worstStress = stressRows.minOf { it.totalPnl.toDouble() }
        val worstCalm = calmRows.minOf { it.totalPnl.toDouble() }

        // Worst stress-window day must be materially worse than the worst calm day.
        // 1.5x is a defensible inequality: it tolerates GARCH noise without being
        // a tautology — if the regime mechanism were ignored, the two would be
        // statistically indistinguishable.
        check(worstStress < worstCalm * 1.5) {
            "Expected worst stress-window P&L ($worstStress) to be at least 1.5x worse " +
                "than worst calm-window P&L ($worstCalm) for equity-growth book"
        }
    }

    test("option-bearing books emit non-zero gamma, vega, theta on at least one row") {
        val deriver = PnLAttributionDeriver()
        deriver.derive().forEach { repo.save(it) }

        val booksWithOptions = DemoBookCatalogue.BOOKS
            .filterValues { positions -> positions.any { it.isOption } }
            .keys

        booksWithOptions.shouldNotBeEmpty()

        booksWithOptions.forEach { bookId ->
            val rows = repo.findByBookId(
                BookId(bookId),
                fromDate = LocalDate.now().minusYears(2),
            )
            val anyGamma = rows.any { it.gammaPnl.signum() != 0 }
            val anyVega = rows.any { it.vegaPnl.signum() != 0 }
            val anyTheta = rows.any { it.thetaPnl.signum() != 0 }
            check(anyGamma) { "Book '$bookId' has no non-zero gamma_pnl in any persisted row" }
            check(anyVega) { "Book '$bookId' has no non-zero vega_pnl in any persisted row" }
            check(anyTheta) { "Book '$bookId' has no non-zero theta_pnl in any persisted row" }
        }
    }

    test("every persisted row ties out: total = sum of all Greek components + residual") {
        val deriver = PnLAttributionDeriver()
        deriver.derive().forEach { repo.save(it) }

        DemoBookCatalogue.BOOKS.keys.forEach { bookId ->
            val rows = repo.findByBookId(
                BookId(bookId),
                fromDate = LocalDate.now().minusYears(2),
            )
            rows.shouldNotBeEmpty()
            rows.forEach { row ->
                val sumOfParts = row.deltaPnl
                    .add(row.gammaPnl).add(row.vegaPnl).add(row.thetaPnl).add(row.rhoPnl)
                    .add(row.vannaPnl).add(row.volgaPnl).add(row.charmPnl).add(row.crossGammaPnl)
                    .add(row.unexplainedPnl)
                row.totalPnl.compareTo(sumOfParts) shouldBe 0
            }
        }
    }
})

private fun partitionByWindows(
    rows: List<PnlAttribution>,
    windows: List<RegimeCalendar.StressWindow>,
): Pair<List<PnlAttribution>, List<PnlAttribution>> {
    return rows.partition { row ->
        windows.any { window -> !row.date.isBefore(window.start) && !row.date.isAfter(window.end) }
    }
}
