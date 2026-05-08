package com.kinetix.common.demo

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 252-trading-day calendar anchored on a most-recent date.
 *
 * Day 0 = most recent trading day; day 251 = oldest.
 *
 * Two stress windows are baked in so historical-VaR queries hit them automatically
 * and Phase 0 consistency checks (Kupiec backtest, vol regime changes) reconcile.
 */
class RegimeCalendar(
    val asOf: Instant = DEFAULT_AS_OF,
    private val stress2020Start: Int = 178,
    private val stress2020End: Int = 184,
    private val stress2022Start: Int = 60,
    private val stress2022End: Int = 90,
    private val preStressLeadIn: Int = 3,
    private val recoveryLength: Int = 7,
) {
    init {
        require(stress2020Start in 1..251 && stress2020End in stress2020Start..251) {
            "2020-analog stress window must be within calendar"
        }
        require(stress2022Start in 1..251 && stress2022End in stress2022Start..251) {
            "2022-analog stress window must be within calendar"
        }
        require(stress2020Start - stress2022End > preStressLeadIn) {
            "stress windows must not overlap with their lead-ins"
        }
    }

    fun regimeForDay(dayIndex: Int): Regime {
        require(dayIndex in 0 until DAYS) { "dayIndex must be 0..${DAYS - 1}" }
        return when {
            dayIndex in stress2020Start..stress2020End -> Regime.STRESS_2020_ANALOG
            dayIndex in (stress2020End + 1)..(stress2020End + recoveryLength) -> Regime.RECOVERY
            dayIndex in (stress2020Start - preStressLeadIn) until stress2020Start -> Regime.PRE_STRESS
            dayIndex in stress2022Start..stress2022End -> Regime.STRESS_2022_ANALOG
            dayIndex in (stress2022End + 1)..(stress2022End + recoveryLength) -> Regime.RECOVERY
            dayIndex in (stress2022Start - preStressLeadIn) until stress2022Start -> Regime.PRE_STRESS
            else -> Regime.CALM
        }
    }

    fun dateForDay(dayIndex: Int): LocalDate {
        require(dayIndex in 0 until DAYS) { "dayIndex must be 0..${DAYS - 1}" }
        var date = LocalDate.ofInstant(asOf, ZoneOffset.UTC)
        // Snap asOf back to a weekday so day 0 is always a trading day.
        while (date.dayOfWeek.value > 5) date = date.minusDays(1)
        var remaining = dayIndex
        while (remaining > 0) {
            date = date.minusDays(1)
            if (date.dayOfWeek.value <= 5) remaining--
        }
        return date
    }

    fun instantForDay(dayIndex: Int): Instant {
        return dateForDay(dayIndex).atStartOfDay(ZoneOffset.UTC).toInstant()
            .plus(asOf.atZone(ZoneOffset.UTC).toLocalTime().toNanoOfDay() / 1_000_000_000, ChronoUnit.SECONDS)
    }

    /** Stress windows for downstream consumers (e.g. as_of date tagging). */
    fun stressWindows(): List<StressWindow> = listOf(
        StressWindow(
            label = "2020-analog",
            start = dateForDay(stress2020End),
            end = dateForDay(stress2020Start),
        ),
        StressWindow(
            label = "2022-analog",
            start = dateForDay(stress2022End),
            end = dateForDay(stress2022Start),
        ),
    )

    data class StressWindow(val label: String, val start: LocalDate, val end: LocalDate)

    companion object {
        const val DAYS: Int = 252
        val DEFAULT_AS_OF: Instant = Instant.parse("2026-02-22T10:00:00Z")
    }
}
