package com.kinetix.demo.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters
import kotlin.math.floor

/**
 * Standard US equity-market closures observed by NYSE / Nasdaq.
 *
 * Returns `true` when [date] is one of:
 *
 *  - **New Year's Day** — Jan 1, with the standard observed-weekday
 *    rollover (Saturday -> preceding Friday, Sunday -> following Monday).
 *  - **Martin Luther King Jr Day** — third Monday of January.
 *  - **Presidents Day** — third Monday of February.
 *  - **Good Friday** — the Friday before Easter Sunday (computed via the
 *    anonymous Gregorian algorithm).
 *  - **Memorial Day** — last Monday of May.
 *  - **Juneteenth** — June 19, with observed-weekday rollover.
 *  - **Independence Day** — July 4, with observed-weekday rollover.
 *  - **Labor Day** — first Monday of September.
 *  - **Thanksgiving Day** — fourth Thursday of November.
 *  - **Christmas Day** — December 25, with observed-weekday rollover.
 *
 * This is a pure function: it has no state, no I/O, and depends only on
 * [date]. It is safe to call for any year — the underlying rules apply
 * uniformly.
 */
fun isUsMarketHoliday(date: LocalDate): Boolean =
    date in holidaysFor(date.year)

private fun holidaysFor(year: Int): Set<LocalDate> {
    val holidays = mutableSetOf<LocalDate>()
    // Fixed-date holidays with observed-weekday rollover.
    holidays += observedDate(LocalDate.of(year, Month.JANUARY, 1))
    holidays += observedDate(LocalDate.of(year, Month.JUNE, 19))
    holidays += observedDate(LocalDate.of(year, Month.JULY, 4))
    holidays += observedDate(LocalDate.of(year, Month.DECEMBER, 25))

    // Nth-weekday-of-month holidays.
    holidays += LocalDate.of(year, Month.JANUARY, 1)
        .with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY))
    holidays += LocalDate.of(year, Month.FEBRUARY, 1)
        .with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY))
    holidays += LocalDate.of(year, Month.MAY, 1)
        .with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY))
    holidays += LocalDate.of(year, Month.SEPTEMBER, 1)
        .with(TemporalAdjusters.dayOfWeekInMonth(1, DayOfWeek.MONDAY))
    holidays += LocalDate.of(year, Month.NOVEMBER, 1)
        .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))

    // Good Friday — Friday before Easter Sunday.
    holidays += easterSunday(year).minusDays(2)

    return holidays
}

/**
 * Applies the standard NYSE / federal observed-weekday rollover:
 * a Saturday holiday is observed on the preceding Friday; a Sunday
 * holiday is observed on the following Monday. Weekday holidays are
 * unchanged.
 */
private fun observedDate(actual: LocalDate): LocalDate = when (actual.dayOfWeek) {
    DayOfWeek.SATURDAY -> actual.minusDays(1)
    DayOfWeek.SUNDAY -> actual.plusDays(1)
    else -> actual
}

/**
 * Anonymous Gregorian algorithm (Meeus / Jones / Butcher) for Easter
 * Sunday in the given [year]. Accurate for all years in the Gregorian
 * calendar.
 */
private fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = floor((a + 11.0 * h + 22.0 * l) / 451.0).toInt()
    val rawMonth = (h + l - 7 * m + 114) / 31
    val rawDay = ((h + l - 7 * m + 114) % 31) + 1
    return LocalDate.of(year, rawMonth, rawDay)
}
