package com.kinetix.position.quantity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PositionQuantity carries SIZE (absolute magnitude); the long/short
 * direction is a separate field on the surrounding domain type. So
 * the quantity itself must be non-negative — a negative size doesn't
 * make sense, it would mean "the position is the negative of having
 * a position", which is what the direction field is for.
 *
 * Zero IS allowed: a flat position (size 0) is the result of a
 * closing trade and shows up in the audit chain as a real event.
 */
class PositionQuantityValidationTest : FunSpec({

    test("accepts a typical positive quantity") {
        validatePositionQuantity(1000L)
    }

    test("accepts zero quantity (flat after close)") {
        validatePositionQuantity(0L)
    }

    test("rejects negative quantity with a domain-specific error") {
        val ex = shouldThrow<IllegalArgumentException> {
            validatePositionQuantity(-1L)
        }
        ex.message!!.contains("PositionQuantity") shouldBe true
        ex.message!!.contains("non-negative") shouldBe true
    }

    test("rejects large negative") {
        shouldThrow<IllegalArgumentException> {
            validatePositionQuantity(-1_000_000L)
        }
    }

    test("accepts a very large positive quantity (no upper bound here)") {
        validatePositionQuantity(Long.MAX_VALUE)
    }
})
