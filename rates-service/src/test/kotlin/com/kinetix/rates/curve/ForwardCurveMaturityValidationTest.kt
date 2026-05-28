package com.kinetix.rates.curve

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * Forward-curve maturities must be in the future relative to the
 * pricing date, monotonically increasing, and within the per-
 * currency horizon supported by the underlying liquidity (e.g. JPY
 * forwards beyond 30 years are illiquid and quotes for those tenors
 * are usually fabricated by the vendor). This validator rejects
 * past-dated, duplicate, and out-of-order maturities.
 */
class ForwardCurveMaturityValidationTest : FunSpec({

    val today = LocalDate.of(2026, 5, 28)

    test("accepts an ordered set of future maturities") {
        validateForwardCurveMaturities(
            today = today,
            maturities = listOf(
                today.plusMonths(3),
                today.plusMonths(6),
                today.plusYears(1),
                today.plusYears(5),
            ),
        )
    }

    test("rejects a past-dated maturity") {
        shouldThrow<IllegalArgumentException> {
            validateForwardCurveMaturities(today, listOf(today.minusDays(1)))
        }
    }

    test("rejects a today-dated maturity (must be strictly future)") {
        shouldThrow<IllegalArgumentException> {
            validateForwardCurveMaturities(today, listOf(today))
        }
    }

    test("rejects duplicate maturities") {
        shouldThrow<IllegalArgumentException> {
            validateForwardCurveMaturities(today, listOf(today.plusMonths(3), today.plusMonths(3)))
        }
    }

    test("rejects out-of-order maturities") {
        shouldThrow<IllegalArgumentException> {
            validateForwardCurveMaturities(today, listOf(today.plusYears(1), today.plusMonths(3)))
        }
    }

    test("rejects an empty maturity list") {
        shouldThrow<IllegalArgumentException> {
            validateForwardCurveMaturities(today, emptyList())
        }
    }

    test("the rejection message names the offending date") {
        val past = today.minusDays(1)
        val ex = shouldThrow<IllegalArgumentException> {
            validateForwardCurveMaturities(today, listOf(past))
        }
        ex.message!!.contains(past.toString()) shouldBe true
    }
})
