package com.kinetix.volatility.skew

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The implied-vol surface's third (skew) and fourth (kurtosis) moments
 * encode market sentiment about tails. A sharp move in either is a
 * leading indicator of a regime change — a flattening skew often
 * precedes a re-rating of crash risk. The detector flags a change
 * when the absolute move in either moment exceeds the configured
 * threshold (defaults: 0.20 for skew, 0.50 for kurtosis).
 */
class SkewKurtosisChangeDetectionTest : FunSpec({

    test("a tiny jitter in both skew and kurtosis is not a change") {
        detectSkewKurtosisChange(prevSkew = 0.1, currSkew = 0.11, prevKurt = 3.0, currKurt = 3.02) shouldBe false
    }

    test("a 0.25 skew move (above default 0.20) is a change") {
        detectSkewKurtosisChange(0.1, 0.35, 3.0, 3.0) shouldBe true
    }

    test("a 0.60 kurtosis move (above default 0.50) is a change") {
        detectSkewKurtosisChange(0.1, 0.11, 3.0, 3.6) shouldBe true
    }

    test("both at threshold is a change (>= inclusive)") {
        detectSkewKurtosisChange(0.0, 0.20, 3.0, 3.50) shouldBe true
    }

    test("a sign-flip in skew is captured by absolute-move comparison") {
        detectSkewKurtosisChange(-0.25, 0.25, 3.0, 3.0) shouldBe true
    }

    test("custom thresholds honoured (tighter skew threshold flags smaller move)") {
        detectSkewKurtosisChange(0.1, 0.20, 3.0, 3.0, skewThreshold = 0.05, kurtosisThreshold = 0.50) shouldBe true
    }

    test("NaN inputs are treated as no-change (defensive)") {
        detectSkewKurtosisChange(Double.NaN, 0.1, 3.0, 3.0) shouldBe false
        detectSkewKurtosisChange(0.1, 0.1, Double.NaN, 3.0) shouldBe false
    }
})
