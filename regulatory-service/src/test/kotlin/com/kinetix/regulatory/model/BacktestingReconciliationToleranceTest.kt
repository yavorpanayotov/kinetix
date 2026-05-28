package com.kinetix.regulatory.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Backtesting reconciles the desk-reported P&L for a trading day against
 * the P&L computed from the canonical trade ledger. The two will almost
 * never match to the last cent — different timestamp truncation, FX
 * pickup timing, dividend accruals booked at different cuts. Compliance
 * accepts a tolerance: absolute mismatch within ±$1k AND relative
 * mismatch within ±0.5% of the trade-derived P&L. Anything wider is a
 * RECONCILIATION_BREAK that has to be investigated before the day's
 * trail is signed off.
 *
 * The function encodes that contract: given the two P&Ls and a
 * tolerance pair, return [ReconciliationStatus.WITHIN_TOLERANCE] or
 * [ReconciliationStatus.BREAK] with the absolute and relative
 * mismatches attached to the latter.
 */
class BacktestingReconciliationToleranceTest : FunSpec({

    val defaultAbs = 1_000.0  // ±$1k
    val defaultRel = 0.005    // ±0.5%

    test("exact match is within tolerance") {
        reconcilePnL(
            tradeDerived = 1_000_000.0,
            deskReported = 1_000_000.0,
            absoluteTolerance = defaultAbs,
            relativeTolerance = defaultRel,
        ) shouldBe ReconciliationStatus.WithinTolerance
    }

    test("within absolute tolerance only ($500 mismatch on small book) passes") {
        reconcilePnL(1_000.0, 1_500.0, defaultAbs, defaultRel) shouldBe
            ReconciliationStatus.WithinTolerance
    }

    test("within relative tolerance only ($4k mismatch on $1M is 0.4%) passes") {
        reconcilePnL(1_000_000.0, 1_004_000.0, defaultAbs, defaultRel) shouldBe
            ReconciliationStatus.WithinTolerance
    }

    test("exceeds both tolerances (\$10k mismatch, 1%) flags BREAK") {
        val result = reconcilePnL(1_000_000.0, 1_010_000.0, defaultAbs, defaultRel)
        result shouldBe ReconciliationStatus.Break(
            absoluteMismatch = 10_000.0,
            relativeMismatch = 0.01,
        )
    }

    test("at exactly the absolute tolerance boundary (within)") {
        // diff = 1000 exactly, abs tol = 1000. Within means <= tolerance.
        reconcilePnL(0.0, 1_000.0, defaultAbs, defaultRel) shouldBe
            ReconciliationStatus.WithinTolerance
    }

    test("just outside the absolute tolerance on a zero-PnL day flags BREAK") {
        val result = reconcilePnL(0.0, 1_000.01, defaultAbs, defaultRel)
        (result is ReconciliationStatus.Break) shouldBe true
    }

    test("zero trade-derived PnL with non-zero reported uses absolute tolerance only") {
        // Relative mismatch is undefined when trade-derived = 0; helper falls
        // back to absolute-only.
        reconcilePnL(0.0, 500.0, defaultAbs, defaultRel) shouldBe
            ReconciliationStatus.WithinTolerance
    }

    test("negative PnLs flag BREAK the same way as positives") {
        val result = reconcilePnL(-1_000_000.0, -1_010_000.0, defaultAbs, defaultRel)
        (result is ReconciliationStatus.Break) shouldBe true
    }
})
