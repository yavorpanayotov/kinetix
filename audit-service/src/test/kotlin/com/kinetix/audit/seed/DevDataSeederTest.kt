package com.kinetix.audit.seed

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.persistence.AuditEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.*

class DevDataSeederTest : FunSpec({

    val repository = mockk<AuditEventRepository>()
    val seeder = DevDataSeeder(repository)

    beforeEach {
        clearMocks(repository)
    }

    test("seeds audit events when anchor trade is absent") {
        coEvery { repository.findByTradeId("seed-eq-aapl-001") } returns null
        coEvery { repository.save(any()) } just runs

        seeder.seed()

        coVerify(exactly = DevDataSeeder.EVENTS.size) { repository.save(any()) }
    }

    test("skips seeding when anchor trade already exists") {
        coEvery { repository.findByTradeId("seed-eq-aapl-001") } returns AuditEvent(
            id = 1,
            tradeId = "seed-eq-aapl-001",
            bookId = "equity-growth",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "25000",
            priceAmount = "185.50",
            priceCurrency = "USD",
            tradedAt = "2026-02-21T14:00:00Z",
            receivedAt = java.time.Instant.now(),
        )

        seeder.seed()

        coVerify(exactly = 0) { repository.save(any()) }
    }

    test("event data has at least 300 events (core + generated)") {
        DevDataSeeder.EVENTS.size shouldBeGreaterThan 299
    }

    test("all seed events have non-null userId and userRole") {
        DevDataSeeder.EVENTS.all { it.userId != null && it.userRole != null } shouldBe true
    }

    test("seed events include at least 4 distinct userIds") {
        val userIds = DevDataSeeder.EVENTS.mapNotNull { it.userId }.distinct()
        userIds.size shouldBeGreaterThan 3
    }

    test("all trade IDs match seed convention; (tradeId, eventType) tuples are unique") {
        val tradeIds = DevDataSeeder.EVENTS.map { it.tradeId }
        // Cancels (Phase 3 Gap 4) intentionally reuse the original tradeId with
        // eventType = TRADE_CANCELLED, so plain tradeId uniqueness no longer
        // holds. The (tradeId, eventType) tuple, however, must remain unique.
        val tuples = DevDataSeeder.EVENTS.map { it.tradeId to it.eventType }
        tuples.distinct().size shouldBe tuples.size
        tradeIds.all { it?.startsWith("seed-") == true } shouldBe true
    }

    test("events cover all eight books") {
        val portfolios = DevDataSeeder.EVENTS.mapNotNull { it.bookId }.distinct().sorted()
        portfolios shouldBe listOf(
            "balanced-income", "derivatives-book", "emerging-markets",
            "equity-growth", "fixed-income", "macro-hedge",
            "multi-asset", "tech-momentum",
        )
    }

    // ── Phase 3 Gap 4 — lifecycle audit event types ───────────────────────

    test("seed includes TRADE_AMENDED events for every amend chain") {
        val amends = DevDataSeeder.EVENTS.filter { it.eventType == "TRADE_AMENDED" }
        amends.size shouldBeGreaterThan 5
        amends.all { it.tradeId?.endsWith("-amend") == true } shouldBe true
        amends.all { it.details?.startsWith("originalTradeId=") == true } shouldBe true
    }

    test("seed includes TRADE_CANCELLED events for every cancel chain") {
        val cancels = DevDataSeeder.EVENTS.filter { it.eventType == "TRADE_CANCELLED" }
        cancels.size shouldBeGreaterThan 5
        // Cancels reuse the original tradeId (transition on same record).
        cancels.all { it.tradeId?.startsWith("seed-gen-ac-") == true } shouldBe true
    }

    test("every TRADE_AMENDED references a TRADE_BOOKED original via details") {
        val bookedIds = DevDataSeeder.EVENTS
            .filter { it.eventType == "TRADE_BOOKED" }
            .mapNotNull { it.tradeId }
            .toSet()
        DevDataSeeder.EVENTS
            .filter { it.eventType == "TRADE_AMENDED" }
            .forEach { amend ->
                val original = amend.details?.removePrefix("originalTradeId=")
                (original in bookedIds) shouldBe true
            }
    }

    test("every TRADE_CANCELLED has a matching TRADE_BOOKED on the same tradeId") {
        val bookedIds = DevDataSeeder.EVENTS
            .filter { it.eventType == "TRADE_BOOKED" }
            .mapNotNull { it.tradeId }
            .toSet()
        DevDataSeeder.EVENTS
            .filter { it.eventType == "TRADE_CANCELLED" }
            .forEach { cancel ->
                (cancel.tradeId in bookedIds) shouldBe true
            }
    }

    test("no audit event uses the legacy -cancel ID suffix from the opposite-side hack") {
        val cancelHackIds = DevDataSeeder.EVENTS
            .mapNotNull { it.tradeId }
            .filter { it.endsWith("-cancel") }
        cancelHackIds shouldBe emptyList()
    }
})
