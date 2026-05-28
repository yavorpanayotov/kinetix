package com.kinetix.correlation.regime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pairwise correlations are stable in most regimes but can jump
 * sharply during stress — "everything correlates to 1 in a crash".
 * Surfacing those jumps as an alert lets the risk desk respond
 * (re-anchor scenarios, tighten limits) before the position book
 * acts on a stale correlation matrix. The detector takes the prior
 * and current correlation pair and flags a regime change when the
 * absolute change exceeds the [threshold] (default 0.3).
 */
class CorrelationRegimeChangeDetectionTest : FunSpec({

    test("a tiny jitter (0.01) is not a regime change") {
        detectCorrelationRegimeChange(previous = 0.50, current = 0.51) shouldBe false
    }

    test("a 0.2 move is not a regime change at the default threshold") {
        detectCorrelationRegimeChange(0.30, 0.50) shouldBe false
    }

    test("a 0.4 move is a regime change") {
        detectCorrelationRegimeChange(0.30, 0.70) shouldBe true
    }

    test("a sign flip from -0.4 to +0.4 (delta 0.8) is a regime change") {
        detectCorrelationRegimeChange(-0.40, 0.40) shouldBe true
    }

    test("a move that drives the pair to a +1 lock-up is a regime change") {
        detectCorrelationRegimeChange(0.30, 1.00) shouldBe true
    }

    test("custom threshold honoured (0.1 threshold flags a 0.15 move)") {
        detectCorrelationRegimeChange(0.30, 0.45, threshold = 0.10) shouldBe true
    }

    test("the threshold is inclusive (>= threshold counts as a change)") {
        detectCorrelationRegimeChange(0.30, 0.60) shouldBe true   // exactly 0.30 delta
    }

    test("NaN inputs are treated as no-change (defensive against missing pairs)") {
        detectCorrelationRegimeChange(Double.NaN, 0.5) shouldBe false
        detectCorrelationRegimeChange(0.5, Double.NaN) shouldBe false
    }
})
