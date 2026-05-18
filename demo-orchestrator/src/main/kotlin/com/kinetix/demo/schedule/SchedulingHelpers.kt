package com.kinetix.demo.schedule

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Helpers for computing recurring fire times for demo-orchestrator schedulers.
 *
 * Pulled out of `Application.kt` so the time arithmetic — easy to get subtly
 * wrong around midnight UTC and the "exactly equal to target" boundary — can
 * be unit-tested without standing up a Ktor application.
 */
internal object SchedulingHelpers {

    /**
     * Returns the [Duration] from [now] until the next occurrence of
     * [targetTimeUtc] (interpreted in UTC).
     *
     * If [now] is strictly before today's target time the duration runs to
     * today's target; otherwise it runs to tomorrow's. The "equal to target"
     * boundary rolls forward to the next day so that a scheduler waking up
     * exactly on the target instant does not immediately re-fire with a zero
     * delay.
     */
    fun durationUntilNext(targetTimeUtc: LocalTime, now: Instant): Duration {
        val nowUtc = now.atZone(ZoneOffset.UTC)
        var target = nowUtc.toLocalDate().atTime(targetTimeUtc).atZone(ZoneOffset.UTC)
        if (!target.isAfter(nowUtc)) {
            target = target.plusDays(1)
        }
        return Duration.between(nowUtc, target)
    }

    /**
     * Convenience overload that pulls "now" from the supplied [Clock]. Default
     * is the system UTC clock so production callers can omit it.
     */
    fun durationUntilNext(
        targetTimeUtc: LocalTime,
        clock: Clock = Clock.systemUTC(),
    ): Duration = durationUntilNext(targetTimeUtc, clock.instant())
}
