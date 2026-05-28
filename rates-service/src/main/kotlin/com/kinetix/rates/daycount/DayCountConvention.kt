package com.kinetix.rates.daycount

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Day-count convention: how to convert a tenor in days into a
 * year-fraction. Three common conventions:
 *   - **ACT/360** — actual elapsed days divided by 360. Money-market
 *     instruments (USD, EUR LIBOR successors).
 *   - **ACT/365** — actual elapsed days divided by 365. Gilts, AUD,
 *     and most plain-vanilla bonds.
 *   - **30/360** — every month treated as 30 days, every year as 360.
 *     Common in fixed-rate swaps. Uses the standard end-of-month
 *     rule: if the start date is the 31st, treat as the 30th.
 *
 * Mixing conventions silently produces accrued-interest mismatches
 * that operations doesn't catch until the coupon settles.
 */
enum class DayCountConvention {
    ACT_360 {
        override fun yearFraction(start: LocalDate, end: LocalDate): Double =
            ChronoUnit.DAYS.between(start, end).toDouble() / 360.0
    },
    ACT_365 {
        override fun yearFraction(start: LocalDate, end: LocalDate): Double =
            ChronoUnit.DAYS.between(start, end).toDouble() / 365.0
    },
    THIRTY_360 {
        override fun yearFraction(start: LocalDate, end: LocalDate): Double {
            val d1 = minOf(start.dayOfMonth, 30)
            val d2 = if (start.dayOfMonth >= 30 && end.dayOfMonth == 31) 30 else end.dayOfMonth
            val days = (end.year - start.year) * 360 +
                (end.monthValue - start.monthValue) * 30 +
                (d2 - d1)
            return days.toDouble() / 360.0
        }
    };

    abstract fun yearFraction(start: LocalDate, end: LocalDate): Double
}
