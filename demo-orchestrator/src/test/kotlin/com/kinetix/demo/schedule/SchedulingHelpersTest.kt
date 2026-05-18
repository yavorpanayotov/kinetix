package com.kinetix.demo.schedule

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

class SchedulingHelpersTest : FunSpec({

    val target = LocalTime.of(6, 5)

    test("rolls forward to today's target when current time is earlier") {
        val now = Instant.parse("2026-05-18T05:00:00Z")

        val duration = SchedulingHelpers.durationUntilNext(target, now)

        duration shouldBe Duration.ofMinutes(65)
    }

    test("rolls forward to tomorrow when current time is after today's target") {
        val now = Instant.parse("2026-05-18T07:00:00Z")

        val duration = SchedulingHelpers.durationUntilNext(target, now)

        duration shouldBe Duration.ofHours(23) + Duration.ofMinutes(5)
    }

    test("rolls forward a full day when current instant equals target instant") {
        val now = Instant.parse("2026-05-18T06:05:00Z")

        val duration = SchedulingHelpers.durationUntilNext(target, now)

        duration shouldBe Duration.ofDays(1)
    }

    test("crossing midnight UTC schedules at most ~24h ahead") {
        val now = Instant.parse("2026-05-18T23:55:00Z")

        val duration = SchedulingHelpers.durationUntilNext(target, now)

        duration shouldBe Duration.ofMinutes(6 * 60 + 10)
    }
})
