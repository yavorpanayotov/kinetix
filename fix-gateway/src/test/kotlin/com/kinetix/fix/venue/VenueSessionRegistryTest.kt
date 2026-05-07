package com.kinetix.fix.venue

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class VenueSessionRegistryTest : FunSpec({

    val registry = VenueSessionRegistry()

    test("default registry knows the five launch venues") {
        listOf("NYSE", "NASDAQ", "LSE", "TSE", "HKEX").forEach { v ->
            registry.lookup(v) shouldNotBe null
        }
    }

    test("normalises lowercase to upper-case") {
        registry.lookup("nyse")?.venue shouldBe "NYSE"
    }

    test("strips surrounding whitespace") {
        registry.lookup("  LSE  ")?.venue shouldBe "LSE"
    }

    test("returns null for unknown venue") {
        registry.lookup("MADEUP") shouldBe null
    }

    test("knows() returns false for unknown venue") {
        registry.knows("MADEUP") shouldBe false
    }

    test("each launch venue has a distinct sender/target comp id") {
        val sessions = registry.all()
        sessions.size shouldBe 5
        // Sender is shared (KINETIX) but target must be unique per venue.
        val targets = sessions.map { it.targetCompId }.toSet()
        targets.size shouldBe sessions.size
    }

    test("co-lo venues advertise tighter PENDING_NEW timeouts than EM") {
        val nyse = registry.lookup("NYSE")!!
        val tse = registry.lookup("TSE")!!
        nyse.defaultVenueAckTimeoutMs shouldBe 200
        tse.defaultVenueAckTimeoutMs shouldBe 1000
    }
})
