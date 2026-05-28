package com.kinetix.rates.interp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp

/**
 * Yield-curve interpolation is the hot path: every fixed-income
 * calculation goes through it. The three interpolators must agree on
 * exact-knot lookups and produce different (but principled) values
 * between knots. The edge-case test pins the contract on:
 *   - exact knot returns the stored value (deterministic)
 *   - mid-tenor produces the expected interp
 *   - below-min / above-max returns the boundary value (flat extrapolation)
 */
class TenorInterpolationEdgeCaseTest : FunSpec({

    val tol = 1e-9
    // Tenor (days) -> zero rate.
    val knots = mapOf(90 to 0.04, 180 to 0.045, 365 to 0.05)

    test("linear: exact knot returns the stored rate") {
        interpolateLinear(knots, 90) shouldBe 0.04
        interpolateLinear(knots, 365) shouldBe 0.05
    }

    test("linear: mid-tenor is the linear midpoint") {
        // Halfway between 90 and 180 -> 0.0425.
        abs(interpolateLinear(knots, 135) - 0.0425) shouldBeLessThanOrEqual tol
    }

    test("linear: below the minimum knot returns the minimum value") {
        interpolateLinear(knots, 1) shouldBe 0.04
    }

    test("linear: above the maximum knot returns the maximum value") {
        interpolateLinear(knots, 730) shouldBe 0.05
    }

    test("log-linear: exact knot returns the stored rate") {
        interpolateLogLinear(knots, 180) shouldBe 0.045
    }

    test("log-linear: mid-tenor uses exp(log-interp)") {
        // log(r135) = 0.5 * (log(0.04) + log(0.045))
        val expected = exp(0.5 * (ln(0.04) + ln(0.045)))
        abs(interpolateLogLinear(knots, 135) - expected) shouldBeLessThanOrEqual tol
    }

    test("log-linear: extrapolates flat outside the knot range") {
        interpolateLogLinear(knots, 1) shouldBe 0.04
        interpolateLogLinear(knots, 730) shouldBe 0.05
    }
})
