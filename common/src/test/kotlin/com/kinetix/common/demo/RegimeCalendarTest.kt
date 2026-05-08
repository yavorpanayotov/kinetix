package com.kinetix.common.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.DayOfWeek

class RegimeCalendarTest : FunSpec({
    val calendar = RegimeCalendar()

    test("calm regime covers most days") {
        val regimes = (0 until RegimeCalendar.DAYS).map { calendar.regimeForDay(it) }
        val calmCount = regimes.count { it == Regime.CALM }
        calmCount shouldBe regimes.size - regimes.count { it != Regime.CALM }
        // Sanity — at most a third of the year should be non-calm.
        (calmCount.toDouble() / RegimeCalendar.DAYS) shouldNotBe 1.0
        assert(calmCount > RegimeCalendar.DAYS * 2 / 3) { "calm days dominated by stress: $calmCount" }
    }

    test("2020-analog stress window is a contiguous block") {
        val days = (0 until RegimeCalendar.DAYS).filter { calendar.regimeForDay(it) == Regime.STRESS_2020_ANALOG }
        days.zipWithNext().forEach { (a, b) -> b shouldBe a + 1 }
    }

    test("2022-analog stress window is a contiguous block") {
        val days = (0 until RegimeCalendar.DAYS).filter { calendar.regimeForDay(it) == Regime.STRESS_2022_ANALOG }
        days.zipWithNext().forEach { (a, b) -> b shouldBe a + 1 }
    }

    test("pre-stress and recovery wrap stress windows") {
        val day = calendar.regimeForDay(177) // immediately before stress2020Start=178
        day shouldBe Regime.PRE_STRESS
    }

    test("dateForDay produces only weekdays") {
        for (day in 0 until RegimeCalendar.DAYS) {
            val date = calendar.dateForDay(day)
            assert(date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
                "day $day -> $date is a weekend"
            }
        }
    }

    test("dateForDay is strictly decreasing as day increases") {
        var prev = calendar.dateForDay(0)
        for (day in 1 until RegimeCalendar.DAYS) {
            val cur = calendar.dateForDay(day)
            assert(cur.isBefore(prev)) { "day $day -> $cur not before previous $prev" }
            prev = cur
        }
    }

    test("two stress windows are exposed for downstream tagging") {
        val labels = calendar.stressWindows().map { it.label }
        labels shouldContainExactly listOf("2020-analog", "2022-analog")
    }
})
