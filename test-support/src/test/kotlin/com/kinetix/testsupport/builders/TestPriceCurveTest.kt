package com.kinetix.testsupport.builders

import com.kinetix.common.model.RateSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Smoke test for [TestPriceCurve]. Verifies the two named factory methods
 * produce well-formed, deterministic [com.kinetix.common.model.ForwardCurve]
 * instances and that the linear shape behaves as documented.
 *
 * Value comparisons use a tight `plusOrMinus` tolerance because [CurvePoint]
 * stores `value` as a `Double`, so arithmetic on the linear shape can
 * accumulate sub-ulp error that doesn't affect equality but should not be
 * compared with exact `==`.
 */
class TestPriceCurveTest : FunSpec({

    test("constant(100.0) produces a curve where every tenor has value 100.0") {
        val curve = TestPriceCurve.constant(100.0)

        curve.points.size shouldBe TestPriceCurve.DEFAULT_TENORS.size
        for (point in curve.points) {
            withClue("Value at tenor=${point.tenor}") {
                point.value shouldBe (100.0 plusOrMinus 1e-12)
            }
        }
    }

    test("constant() default produces a valid curve with the documented default metadata") {
        val curve = TestPriceCurve.constant()

        curve.instrumentId shouldBe TestPriceCurve.DEFAULT_INSTRUMENT_ID
        curve.assetClass shouldBe TestPriceCurve.DEFAULT_ASSET_CLASS
        curve.asOfDate shouldBe TestPriceCurve.DEFAULT_AS_OF
        curve.source shouldBe RateSource.INTERNAL
        curve.points.map { it.tenor } shouldBe TestPriceCurve.DEFAULT_TENORS
        // Default price is 100.0 — every value should be 100.0.
        for (point in curve.points) {
            point.value shouldBe (100.0 plusOrMinus 1e-12)
        }
    }

    test("linear(100.0, 1.0) is strictly increasing across the default tenor grid") {
        val curve = TestPriceCurve.linear(100.0, 1.0)

        curve.points.size shouldBe TestPriceCurve.DEFAULT_TENORS.size
        for (i in 1 until curve.points.size) {
            val prev = curve.points[i - 1].value
            val next = curve.points[i].value
            withClue("Linear curve must be increasing: value[${i - 1}]=$prev vs value[$i]=$next") {
                (next > prev) shouldBe true
            }
        }
        // The first point is start, the last is start + slope * (n - 1).
        curve.points.first().value shouldBe (100.0 plusOrMinus 1e-12)
        val expectedLast = 100.0 + 1.0 * (TestPriceCurve.DEFAULT_TENORS.size - 1)
        curve.points.last().value shouldBe (expectedLast plusOrMinus 1e-12)
    }

    test("linear(start = 100.0, slope = -1.0) is strictly decreasing") {
        val curve = TestPriceCurve.linear(start = 100.0, slope = -1.0)

        for (i in 1 until curve.points.size) {
            val prev = curve.points[i - 1].value
            val next = curve.points[i].value
            withClue("Linear curve must be decreasing: value[${i - 1}]=$prev vs value[$i]=$next") {
                (next < prev) shouldBe true
            }
        }
    }

    test("linear(slope = 0.0) collapses to a flat curve equal to start") {
        val curve = TestPriceCurve.linear(start = 100.0, slope = 0.0)

        for (point in curve.points) {
            point.value shouldBe (100.0 plusOrMinus 1e-12)
        }
    }

    test("constant is deterministic — two calls with the same arg produce equal curves") {
        val a = TestPriceCurve.constant(100.0)
        val b = TestPriceCurve.constant(100.0)

        a shouldBe b
    }

    test("linear is deterministic — two calls with the same args produce equal curves") {
        val a = TestPriceCurve.linear(start = 100.0, slope = 1.0)
        val b = TestPriceCurve.linear(start = 100.0, slope = 1.0)

        a shouldBe b
    }
})
