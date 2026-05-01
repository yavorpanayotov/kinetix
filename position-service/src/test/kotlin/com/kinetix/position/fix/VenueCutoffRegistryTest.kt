package com.kinetix.position.fix

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneId

class VenueCutoffRegistryTest : FunSpec({

    val registry = VenueCutoffRegistry()

    fun ny(localTime: String, year: Int = 2026, month: Int = 5, day: Int = 4): Instant =
        ZonedDateTime.of(year, month, day,
            localTime.substringBefore(":").toInt(),
            localTime.substringAfter(":").toInt(),
            0, 0, ZoneId.of("America/New_York")).toInstant()

    fun london(localTime: String, year: Int = 2026, month: Int = 5, day: Int = 4): Instant =
        ZonedDateTime.of(year, month, day,
            localTime.substringBefore(":").toInt(),
            localTime.substringAfter(":").toInt(),
            0, 0, ZoneId.of("Europe/London")).toInstant()

    fun tokyo(localTime: String, year: Int = 2026, month: Int = 5, day: Int = 4): Instant =
        ZonedDateTime.of(year, month, day,
            localTime.substringBefore(":").toInt(),
            localTime.substringAfter(":").toInt(),
            0, 0, ZoneId.of("Asia/Tokyo")).toInstant()

    test("NYSE session is open at 15:59 ET on a weekday") {
        registry.isSessionClosed("NYSE", ny("15:59")) shouldBe false
    }

    test("NYSE session is closed at 16:00 ET on a weekday (cutoff is inclusive of close-time)") {
        registry.isSessionClosed("NYSE", ny("16:00")) shouldBe true
    }

    test("NYSE session is closed at 16:01 ET on a weekday") {
        registry.isSessionClosed("NYSE", ny("16:01")) shouldBe true
    }

    test("NASDAQ shares the NYSE cutoff (both close at 16:00 ET)") {
        registry.isSessionClosed("NASDAQ", ny("16:00")) shouldBe true
        registry.isSessionClosed("NASDAQ", ny("15:30")) shouldBe false
    }

    test("LSE closes at 16:30 GMT/BST") {
        registry.isSessionClosed("LSE", london("16:00")) shouldBe false
        registry.isSessionClosed("LSE", london("16:30")) shouldBe true
    }

    test("TSE closes at 15:00 JST") {
        registry.isSessionClosed("TSE", tokyo("14:59")) shouldBe false
        registry.isSessionClosed("TSE", tokyo("15:00")) shouldBe true
    }

    test("any venue is closed on a Saturday regardless of clock time") {
        // Saturday 2026-05-02
        val saturdayMidday = ZonedDateTime.of(2026, 5, 2, 12, 0, 0, 0, ZoneId.of("America/New_York"))
            .toInstant()
        registry.isSessionClosed("NYSE", saturdayMidday) shouldBe true
        registry.isSessionClosed("LSE", saturdayMidday) shouldBe true
    }

    test("any venue is closed on a Sunday regardless of clock time") {
        val sundayMidday = ZonedDateTime.of(2026, 5, 3, 12, 0, 0, 0, ZoneId.of("America/New_York"))
            .toInstant()
        registry.isSessionClosed("NYSE", sundayMidday) shouldBe true
    }

    test("unknown venue falls back to NYSE behaviour") {
        // 16:30 ET — past NYSE cutoff
        registry.isSessionClosed("UNKNOWN-VENUE", ny("16:30")) shouldBe true
        registry.isSessionClosed("UNKNOWN-VENUE", ny("10:30")) shouldBe false
    }

    test("venue lookup is case-insensitive") {
        registry.isSessionClosed("nyse", ny("16:00")) shouldBe true
        registry.isSessionClosed("Lse", london("16:30")) shouldBe true
    }

    test("default registry exposes max-GTD horizon for each venue") {
        registry.maxGtdDays("NYSE") shouldBe 90
        registry.maxGtdDays("LSE") shouldBe 90
        registry.maxGtdDays("TSE") shouldBe 90
        registry.maxGtdDays("HKEX") shouldBe 90
    }

    test("custom registry can override venue cutoffs for tests") {
        val custom = VenueCutoffRegistry(
            cutoffs = mapOf(
                "TEST_VENUE" to VenueCutoff(
                    venue = "TEST_VENUE",
                    zone = ZoneId.of("UTC"),
                    dayCutoff = java.time.LocalTime.of(12, 0),
                    maxGtdDays = 30,
                ),
            ),
            fallbackVenue = "TEST_VENUE",
        )
        val noonUtc = ZonedDateTime.of(2026, 5, 4, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant()
        custom.isSessionClosed("TEST_VENUE", noonUtc) shouldBe true
        custom.maxGtdDays("TEST_VENUE") shouldBe 30
    }
})
