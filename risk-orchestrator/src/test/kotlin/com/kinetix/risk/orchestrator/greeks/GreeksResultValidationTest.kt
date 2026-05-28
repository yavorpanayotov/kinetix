package com.kinetix.risk.orchestrator.greeks

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * GreeksResult ships from the risk-engine to downstream consumers
 * (limit checks, P&L attribution, the UI). The numbers must be
 * finite — NaN or +/-Infinity in any Greek slot indicates the
 * upstream calc broke (a vol of zero, a Black-Scholes input out of
 * domain, etc.) and propagating those values silently corrupts every
 * downstream number derived from them. The validator pins down the
 * contract: every Greek must be finite.
 *
 * Note: the validator deliberately does NOT reject negative delta /
 * gamma / vega values — short positions have negative delta and
 * vega, and short-option positions have negative gamma. Rejecting
 * negatives wholesale would block the most common short-vol books
 * the platform serves.
 */
class GreeksResultValidationTest : FunSpec({

    fun ok(): GreeksResult = GreeksResult(
        delta = 1.0, gamma = 0.02, vega = 100.0, theta = -5.0, rho = 50.0,
    )

    test("accepts a typical positive-Greeks result") {
        validateGreeksResult(ok()) shouldBe ok()
    }

    test("accepts negative delta (short position)") {
        val r = ok().copy(delta = -1.0)
        validateGreeksResult(r) shouldBe r
    }

    test("accepts negative gamma (short option position)") {
        val r = ok().copy(gamma = -0.02)
        validateGreeksResult(r) shouldBe r
    }

    test("accepts negative vega (short vol position)") {
        val r = ok().copy(vega = -100.0)
        validateGreeksResult(r) shouldBe r
    }

    test("rejects NaN delta") {
        shouldThrow<IllegalArgumentException> {
            validateGreeksResult(ok().copy(delta = Double.NaN))
        }
    }

    test("rejects Infinity in any Greek") {
        for (mut in listOf<GreeksResult.() -> GreeksResult>(
            { copy(delta = Double.POSITIVE_INFINITY) },
            { copy(gamma = Double.NEGATIVE_INFINITY) },
            { copy(vega = Double.POSITIVE_INFINITY) },
            { copy(theta = Double.NEGATIVE_INFINITY) },
            { copy(rho = Double.POSITIVE_INFINITY) },
        )) {
            shouldThrow<IllegalArgumentException> { validateGreeksResult(mut(ok())) }
        }
    }

    test("rejects NaN in theta and rho too") {
        shouldThrow<IllegalArgumentException> { validateGreeksResult(ok().copy(theta = Double.NaN)) }
        shouldThrow<IllegalArgumentException> { validateGreeksResult(ok().copy(rho = Double.NaN)) }
    }

    test("the rejection message names the offending Greek") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateGreeksResult(ok().copy(vega = Double.NaN))
        }
        ex.message!!.contains("vega") shouldBe true
    }
})
