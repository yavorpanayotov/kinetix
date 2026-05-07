package com.kinetix.fix.venue

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class VenueCutoffRegistryTest : FunSpec({

    val registry = VenueCutoffRegistry()

    test("NYSE is open at 14:00 ET on a weekday") {
        val instant = Instant.parse("2026-05-04T18:00:00Z") // 14:00 ET Mon
        registry.isOpen("NYSE", instant) shouldBe true
    }

    test("NYSE is closed after 16:00 ET on a weekday") {
        val instant = Instant.parse("2026-05-04T20:30:00Z") // 16:30 ET Mon
        registry.isOpen("NYSE", instant) shouldBe false
    }

    test("NYSE is closed before 09:30 ET on a weekday") {
        val instant = Instant.parse("2026-05-04T13:15:00Z") // 09:15 ET Mon
        registry.isOpen("NYSE", instant) shouldBe false
    }

    test("NYSE is closed on Saturday") {
        val instant = Instant.parse("2026-05-09T18:00:00Z") // Saturday afternoon ET
        registry.isOpen("NYSE", instant) shouldBe false
    }

    test("NYSE is closed on Sunday") {
        val instant = Instant.parse("2026-05-10T18:00:00Z") // Sunday afternoon ET
        registry.isOpen("NYSE", instant) shouldBe false
    }

    test("LSE uses London time zone") {
        // 16:00 BST on a weekday — LSE closes at 16:30 BST so still open
        val openInstant = Instant.parse("2026-05-04T15:00:00Z")
        registry.isOpen("LSE", openInstant) shouldBe true
        // 16:35 BST — past LSE cutoff
        val closedInstant = Instant.parse("2026-05-04T15:35:00Z")
        registry.isOpen("LSE", closedInstant) shouldBe false
    }

    test("TSE uses Tokyo time zone") {
        // 14:00 JST Monday — TSE closes at 15:00, so open
        val openInstant = Instant.parse("2026-05-04T05:00:00Z")
        registry.isOpen("TSE", openInstant) shouldBe true
        // 15:05 JST Monday — TSE closed
        val closedInstant = Instant.parse("2026-05-04T06:05:00Z")
        registry.isOpen("TSE", closedInstant) shouldBe false
    }

    test("isSessionClosed is the inverse of isOpen") {
        val instant = Instant.parse("2026-05-04T20:30:00Z")
        registry.isOpen("NYSE", instant) shouldBe false
        registry.isSessionClosed("NYSE", instant) shouldBe true
    }

    test("unknown venue falls back to NYSE behaviour") {
        val instant = Instant.parse("2026-05-04T18:00:00Z") // 14:00 ET Mon — NYSE open
        registry.isOpen("MADEUP_VENUE", instant) shouldBe true
    }

    test("knows() distinguishes registered from unknown venues") {
        registry.knows("NYSE") shouldBe true
        registry.knows("nyse") shouldBe true
        registry.knows("MADEUP") shouldBe false
    }

    test("nextClose returns today's cutoff when before cutoff") {
        val now = Instant.parse("2026-05-04T18:00:00Z") // 14:00 ET Mon
        val expectedClose = Instant.parse("2026-05-04T20:00:00Z") // 16:00 ET Mon
        registry.nextClose("NYSE", now) shouldBe expectedClose
    }

    test("nextClose rolls forward when at or past today's cutoff") {
        val now = Instant.parse("2026-05-04T20:30:00Z") // 16:30 ET Mon — past cutoff
        val expectedClose = Instant.parse("2026-05-05T20:00:00Z") // 16:00 ET Tue
        registry.nextClose("NYSE", now) shouldBe expectedClose
    }

    test("nextClose skips weekends") {
        val now = Instant.parse("2026-05-08T20:30:00Z") // 16:30 ET Fri — past cutoff
        val expectedClose = Instant.parse("2026-05-11T20:00:00Z") // 16:00 ET Mon
        registry.nextClose("NYSE", now) shouldBe expectedClose
    }

    test("custom registry honours fallback venue") {
        val custom = VenueCutoffRegistry(
            cutoffs = mapOf(
                "ICE" to VenueCutoff(
                    venue = "ICE",
                    zone = ZoneId.of("UTC"),
                    dayOpen = LocalTime.of(0, 0),
                    dayCutoff = LocalTime.of(23, 59),
                    maxGtdDays = 30,
                ),
            ),
            fallbackVenue = "ICE",
        )
        custom.maxGtdDays("UNKNOWN") shouldBe 30
    }
})
