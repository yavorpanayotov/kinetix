package com.kinetix.demo.schedule

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * Unit tests for [UsMarketHolidayCalendar].
 *
 * The calendar covers the standard US equity-market closures observed by
 * NYSE/Nasdaq: New Year's Day, Martin Luther King Jr Day, Presidents Day,
 * Good Friday, Memorial Day, Juneteenth, Independence Day, Labor Day,
 * Thanksgiving, and Christmas. When a fixed-date holiday falls on a
 * weekend the calendar applies the standard observed-weekday rollover
 * (Saturday -> preceding Friday, Sunday -> following Monday).
 */
class UsMarketHolidayCalendarTest : FunSpec({

    test("recognises 2026 NYSE/Nasdaq holidays") {
        // Source: NYSE 2026 holiday calendar.
        // New Year's Day — Thursday.
        isUsMarketHoliday(LocalDate.of(2026, 1, 1)) shouldBe true
        // MLK Day — third Monday of January = 19th.
        isUsMarketHoliday(LocalDate.of(2026, 1, 19)) shouldBe true
        // Presidents Day — third Monday of February = 16th.
        isUsMarketHoliday(LocalDate.of(2026, 2, 16)) shouldBe true
        // Good Friday 2026 — April 3.
        isUsMarketHoliday(LocalDate.of(2026, 4, 3)) shouldBe true
        // Memorial Day — last Monday of May = 25th.
        isUsMarketHoliday(LocalDate.of(2026, 5, 25)) shouldBe true
        // Juneteenth — Friday June 19.
        isUsMarketHoliday(LocalDate.of(2026, 6, 19)) shouldBe true
        // Independence Day — July 4 = Saturday → observed Friday July 3.
        isUsMarketHoliday(LocalDate.of(2026, 7, 3)) shouldBe true
        // Labor Day — first Monday of September = 7th.
        isUsMarketHoliday(LocalDate.of(2026, 9, 7)) shouldBe true
        // Thanksgiving — fourth Thursday of November = 26th.
        isUsMarketHoliday(LocalDate.of(2026, 11, 26)) shouldBe true
        // Christmas — December 25 = Friday, no rollover.
        isUsMarketHoliday(LocalDate.of(2026, 12, 25)) shouldBe true
    }

    test("recognises 2027 NYSE/Nasdaq holidays") {
        // Source: NYSE 2027 holiday calendar.
        // New Year's Day — January 1 = Friday.
        isUsMarketHoliday(LocalDate.of(2027, 1, 1)) shouldBe true
        // MLK Day — third Monday of January = 18th.
        isUsMarketHoliday(LocalDate.of(2027, 1, 18)) shouldBe true
        // Presidents Day — third Monday of February = 15th.
        isUsMarketHoliday(LocalDate.of(2027, 2, 15)) shouldBe true
        // Good Friday 2027 — March 26.
        isUsMarketHoliday(LocalDate.of(2027, 3, 26)) shouldBe true
        // Memorial Day — last Monday of May = 31st.
        isUsMarketHoliday(LocalDate.of(2027, 5, 31)) shouldBe true
        // Juneteenth — June 19 = Saturday → observed Friday June 18.
        isUsMarketHoliday(LocalDate.of(2027, 6, 18)) shouldBe true
        // Independence Day — July 4 = Sunday → observed Monday July 5.
        isUsMarketHoliday(LocalDate.of(2027, 7, 5)) shouldBe true
        // Labor Day — first Monday of September = 6th.
        isUsMarketHoliday(LocalDate.of(2027, 9, 6)) shouldBe true
        // Thanksgiving — fourth Thursday of November = 25th.
        isUsMarketHoliday(LocalDate.of(2027, 11, 25)) shouldBe true
        // Christmas — December 25 = Saturday → observed Friday December 24.
        isUsMarketHoliday(LocalDate.of(2027, 12, 24)) shouldBe true
    }

    test("ordinary weekdays are not flagged as holidays") {
        // A random Tuesday in the middle of the year.
        isUsMarketHoliday(LocalDate.of(2026, 5, 19)) shouldBe false
        // The day before a fixed holiday is still a trading day.
        isUsMarketHoliday(LocalDate.of(2026, 12, 24)) shouldBe false
    }

    test("does not flag the literal weekend date when a holiday rolls over") {
        // July 4 2026 is a Saturday; the holiday is observed on Friday the
        // 3rd, not on the literal Saturday.
        isUsMarketHoliday(LocalDate.of(2026, 7, 4)) shouldBe false
        // Sunday Juneteenth 2027 — observed Friday June 18, so the literal
        // 19th is not a market holiday.
        isUsMarketHoliday(LocalDate.of(2027, 6, 19)) shouldBe false
    }
})
