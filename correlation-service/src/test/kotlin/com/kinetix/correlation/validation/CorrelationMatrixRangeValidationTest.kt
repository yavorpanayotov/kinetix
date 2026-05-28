package com.kinetix.correlation.validation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A correlation matrix by definition has 1.0 on the diagonal and
 * values in [-1, 1] off-diagonal. Anything outside those ranges is
 * either bad data (a misnamed covariance entry) or a numerical-
 * conditioning issue (eigen-clip overshoot during PSD repair). The
 * range check is the cheapest sanity gate the platform can run.
 */
class CorrelationMatrixRangeValidationTest : FunSpec({

    val identity = listOf(
        listOf(1.0, 0.0),
        listOf(0.0, 1.0),
    )

    test("accepts the 2x2 identity") {
        validateCorrelationMatrixRange(identity)
    }

    test("accepts a valid off-diagonal at +0.5") {
        validateCorrelationMatrixRange(listOf(
            listOf(1.0, 0.5),
            listOf(0.5, 1.0),
        ))
    }

    test("accepts -1 and +1 at the boundaries") {
        validateCorrelationMatrixRange(listOf(
            listOf(1.0, -1.0),
            listOf(-1.0, 1.0),
        ))
        validateCorrelationMatrixRange(listOf(
            listOf(1.0, 1.0),
            listOf(1.0, 1.0),
        ))
    }

    test("rejects diagonal != 1.0") {
        shouldThrow<IllegalArgumentException> {
            validateCorrelationMatrixRange(listOf(
                listOf(0.99, 0.0),
                listOf(0.0, 1.0),
            ))
        }
    }

    test("rejects off-diagonal > 1") {
        shouldThrow<IllegalArgumentException> {
            validateCorrelationMatrixRange(listOf(
                listOf(1.0, 1.01),
                listOf(1.01, 1.0),
            ))
        }
    }

    test("rejects off-diagonal < -1") {
        shouldThrow<IllegalArgumentException> {
            validateCorrelationMatrixRange(listOf(
                listOf(1.0, -1.01),
                listOf(-1.01, 1.0),
            ))
        }
    }

    test("rejects non-square matrix") {
        shouldThrow<IllegalArgumentException> {
            validateCorrelationMatrixRange(listOf(
                listOf(1.0, 0.0),
                listOf(0.0, 1.0, 0.0),
            ))
        }
    }

    test("rejects empty matrix") {
        shouldThrow<IllegalArgumentException> {
            validateCorrelationMatrixRange(emptyList())
        }
    }
})
