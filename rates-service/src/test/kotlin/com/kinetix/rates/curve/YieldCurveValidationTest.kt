package com.kinetix.rates.curve

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A yield curve is `(tenorDays, rate)` points. The validator enforces
 * the structural invariants: non-empty, strictly increasing tenors,
 * and finite rates. Note that we do NOT reject negative rates — the
 * European Central Bank set its deposit rate to -0.50% from 2019
 * through 2022, and any system that refused to ingest those would
 * have failed during the negative-rates era.
 */
class YieldCurveValidationTest : FunSpec({

    test("accepts a typical positive-rate curve") {
        validateYieldCurve(listOf(30 to 0.04, 90 to 0.045, 365 to 0.05))
    }

    test("accepts a negative-rate curve (post-2008 European reality)") {
        validateYieldCurve(listOf(30 to -0.005, 90 to -0.003, 365 to 0.0))
    }

    test("rejects an empty curve") {
        shouldThrow<IllegalArgumentException> {
            validateYieldCurve(emptyList())
        }
    }

    test("rejects duplicate tenors") {
        shouldThrow<IllegalArgumentException> {
            validateYieldCurve(listOf(90 to 0.04, 90 to 0.045))
        }
    }

    test("rejects out-of-order tenors (must be strictly increasing)") {
        shouldThrow<IllegalArgumentException> {
            validateYieldCurve(listOf(90 to 0.04, 30 to 0.045))
        }
    }

    test("rejects non-positive tenor (must be > 0 days)") {
        shouldThrow<IllegalArgumentException> {
            validateYieldCurve(listOf(0 to 0.04))
        }
        shouldThrow<IllegalArgumentException> {
            validateYieldCurve(listOf(-1 to 0.04))
        }
    }

    test("rejects NaN rate") {
        shouldThrow<IllegalArgumentException> {
            validateYieldCurve(listOf(90 to Double.NaN))
        }
    }

    test("rejects Infinity rate") {
        shouldThrow<IllegalArgumentException> {
            validateYieldCurve(listOf(90 to Double.POSITIVE_INFINITY))
        }
    }

    test("error message names the offending tenor pair on disorder") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateYieldCurve(listOf(90 to 0.04, 30 to 0.045))
        }
        ex.message!!.contains("30") shouldBe true
        ex.message!!.contains("90") shouldBe true
    }
})
