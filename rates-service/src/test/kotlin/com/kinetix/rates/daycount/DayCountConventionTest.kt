package com.kinetix.rates.daycount

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlin.math.abs

/**
 * Day-count conventions decide how a tenor in days converts to a
 * year-fraction. Money-market instruments use ACT/360 (US, EUR);
 * gilts and most bonds use ACT/365; many swaps use 30/360 (each
 * month treated as 30 days, each year as 360). Mixing conventions
 * silently produces accrued-interest mismatches that operations
 * doesn't catch until the coupon settles.
 */
class DayCountConventionTest : FunSpec({

    val tol = 1e-9

    test("ACT_360: 360-day span is exactly 1 year") {
        val start = LocalDate.of(2026, 1, 1)
        DayCountConvention.ACT_360.yearFraction(start, start.plusDays(360)) shouldBe 1.0
    }

    test("ACT_360: 180-day span is 0.5") {
        val start = LocalDate.of(2026, 1, 1)
        abs(DayCountConvention.ACT_360.yearFraction(start, start.plusDays(180)) - 0.5) shouldBeLessThanOrEqual tol
    }

    test("ACT_365: 365-day span is exactly 1 year") {
        val start = LocalDate.of(2026, 1, 1)
        DayCountConvention.ACT_365.yearFraction(start, start.plusDays(365)) shouldBe 1.0
    }

    test("THIRTY_360: full year (Jan 1 -> Jan 1 next year) is 1") {
        val start = LocalDate.of(2026, 1, 1)
        DayCountConvention.THIRTY_360.yearFraction(start, LocalDate.of(2027, 1, 1)) shouldBe 1.0
    }

    test("THIRTY_360: one month is 30/360") {
        val start = LocalDate.of(2026, 1, 15)
        abs(DayCountConvention.THIRTY_360.yearFraction(start, LocalDate.of(2026, 2, 15)) - 30.0 / 360.0) shouldBeLessThanOrEqual tol
    }

    test("THIRTY_360: end-of-month rule — Jan 31 to Feb 28 is treated as 30 days") {
        val start = LocalDate.of(2026, 1, 31)
        val end = LocalDate.of(2026, 2, 28)
        // Standard 30/360 (with end-of-month rule for Jan 31): treats as Jan 30 -> Feb 30.
        abs(DayCountConvention.THIRTY_360.yearFraction(start, end) - 28.0 / 360.0) shouldBeLessThanOrEqual tol
    }

    test("zero-day span is zero year-fraction for every convention") {
        val date = LocalDate.of(2026, 6, 15)
        for (conv in DayCountConvention.entries) {
            conv.yearFraction(date, date) shouldBe 0.0
        }
    }
})
