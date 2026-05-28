package com.kinetix.refdata.credit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Credit spreads are by definition positive — they encode the
 * additional yield a credit-risky issuer pays over the risk-free
 * curve. A negative spread means either (a) bad data — common during
 * an illiquid quote when the bid/ask straddles the risk-free yield —
 * or (b) the curve and spread are referencing different anchors,
 * which is a referential bug that must not propagate into the risk
 * calc. The validator also enforces tenor alignment: the spread tenor
 * must be one the yield curve actually has, so the OAS computation
 * has a meaningful zero-rate to discount against.
 */
class CreditSpreadValidationTest : FunSpec({

    val curveTenorsDays = listOf(1, 7, 30, 90, 180, 365, 730)

    test("accepts a typical positive spread at a curve tenor") {
        validateCreditSpread(spreadBp = 150, tenorDays = 90, curveTenorsDays = curveTenorsDays) shouldBe 150
    }

    test("accepts a zero-spread quote (rare but valid)") {
        validateCreditSpread(0, 90, curveTenorsDays) shouldBe 0
    }

    test("rejects a negative spread") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateCreditSpread(-1, 90, curveTenorsDays)
        }
        ex.message!!.contains("negative") shouldBe true
    }

    test("rejects a tenor not on the curve") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateCreditSpread(150, 45, curveTenorsDays)
        }
        ex.message!!.contains("tenor") shouldBe true
        ex.message!!.contains("45") shouldBe true
    }

    test("rejects an empty curve-tenor list (no anchor available)") {
        shouldThrow<IllegalArgumentException> {
            validateCreditSpread(150, 90, emptyList())
        }
    }

    test("accepts spreads at each curve tenor") {
        for (tenor in curveTenorsDays) {
            validateCreditSpread(75, tenor, curveTenorsDays) shouldBe 75
        }
    }
})
