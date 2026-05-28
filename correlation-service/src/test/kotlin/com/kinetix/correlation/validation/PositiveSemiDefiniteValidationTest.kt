package com.kinetix.correlation.validation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec

/**
 * A correlation matrix must be positive semi-definite (PSD) for the
 * downstream VaR Monte-Carlo / Cholesky decomposition to be valid. A
 * non-PSD matrix slipping through produces "negative variance" results
 * in the simulation and the regulator is going to ask hard questions.
 *
 * This test focuses on the 2x2 case where PSD-ness reduces to
 * `1 - rho^2 >= 0`, equivalent to `|rho| <= 1`. (The full N-dimensional
 * case relies on eigenvalue checks; here we pin down the simplest
 * non-trivial case.)
 */
class PositiveSemiDefiniteValidationTest : FunSpec({

    test("accepts the 2x2 identity") {
        validatePositiveSemiDefinite2x2(listOf(
            listOf(1.0, 0.0),
            listOf(0.0, 1.0),
        ))
    }

    test("accepts a matrix with rho = 0.99") {
        validatePositiveSemiDefinite2x2(listOf(
            listOf(1.0, 0.99),
            listOf(0.99, 1.0),
        ))
    }

    test("accepts rho = 1.0 at the boundary") {
        validatePositiveSemiDefinite2x2(listOf(
            listOf(1.0, 1.0),
            listOf(1.0, 1.0),
        ))
    }

    test("accepts rho = -1.0 at the boundary") {
        validatePositiveSemiDefinite2x2(listOf(
            listOf(1.0, -1.0),
            listOf(-1.0, 1.0),
        ))
    }

    test("rejects rho > 1 (would yield negative determinant)") {
        shouldThrow<IllegalArgumentException> {
            validatePositiveSemiDefinite2x2(listOf(
                listOf(1.0, 1.01),
                listOf(1.01, 1.0),
            ))
        }
    }

    test("rejects rho < -1") {
        shouldThrow<IllegalArgumentException> {
            validatePositiveSemiDefinite2x2(listOf(
                listOf(1.0, -1.01),
                listOf(-1.01, 1.0),
            ))
        }
    }

    test("rejects non-2x2 input") {
        shouldThrow<IllegalArgumentException> {
            validatePositiveSemiDefinite2x2(listOf(listOf(1.0)))
        }
    }
})
