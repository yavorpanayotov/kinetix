package com.kinetix.notification

import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.notification.delivery.DeliveryRouter
import com.kinetix.notification.delivery.InAppDeliveryService
import com.kinetix.notification.engine.RulesEngine
import com.kinetix.notification.model.AlertRule
import com.kinetix.notification.model.AlertStatus
import com.kinetix.notification.model.AlertType
import com.kinetix.notification.model.ComparisonOperator
import com.kinetix.notification.model.DeliveryChannel
import com.kinetix.notification.model.Severity
import com.kinetix.notification.persistence.AlertAcknowledgementsTable
import com.kinetix.notification.persistence.AlertEventsTable
import com.kinetix.notification.persistence.AlertRulesTable
import com.kinetix.notification.persistence.DatabaseTestSetup
import com.kinetix.notification.persistence.ExposedAlertEventRepository
import com.kinetix.notification.persistence.ExposedAlertRuleRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * End-to-end deduplication contract for alert delivery (trader review §20 —
 * "Alerts pile up forever": same VaR breach repeated verbatim, no suppression).
 *
 * Two complementary guarantees:
 *
 *  1. A burst of identical risk-result events for the same `(ruleId, bookId)`
 *     within the suppression window collapses to a single stored alert row.
 *     This is the "5x in 8 seconds" case from real telemetry.
 *
 *  2. After an alert is resolved (or acknowledged), an immediately-following
 *     re-fire of the *same* rule on the *same* book inside the suppression
 *     window is dropped — the desk has already triaged the underlying event
 *     and shouldn't be paged again seconds later. Only once the window has
 *     elapsed does a fresh alert row get created.
 *
 * Suppression window is wall-clock based, anchored on the most-recent
 * `triggeredAt` for the `(ruleId, bookId)` pair. Tests pin the engine's clock
 * so the window edges are deterministic.
 */
class AlertDedupAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()

    beforeEach {
        transaction(db) {
            AlertAcknowledgementsTable.deleteAll()
            AlertEventsTable.deleteAll()
            AlertRulesTable.deleteAll()
        }
    }

    fun varRule(): AlertRule = AlertRule(
        id = "dedup-rule-var",
        name = "VaR Breach Alert",
        type = AlertType.VAR_BREACH,
        threshold = 1_000_000.0,
        operator = ComparisonOperator.GREATER_THAN,
        severity = Severity.CRITICAL,
        channels = listOf(DeliveryChannel.IN_APP),
    )

    fun breachEvent(at: Instant): RiskResultEvent = RiskResultEvent(
        bookId = "derivatives-book",
        varValue = "1372142.47",
        expectedShortfall = "1500000.0",
        calculationType = "PARAMETRIC",
        calculatedAt = at.toString(),
    )

    test("a rule fires 5 times within 5 minutes for the same (rule, book) — only one alert is stored and delivered") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        // Pin the clock so the 5-min suppression window is deterministic.
        val t0 = Instant.parse("2026-05-28T14:00:00Z")
        val fixedClock = Clock.fixed(t0, ZoneOffset.UTC)
        val rulesEngine = RulesEngine(
            ruleRepo,
            eventRepository = eventRepo,
            suppressionWindowSeconds = 300L,
            clock = fixedClock,
        )
        val inApp = InAppDeliveryService(eventRepo)
        val router = DeliveryRouter(listOf(inApp))

        rulesEngine.addRule(varRule())

        // Fire the same breach five times, simulating burst telemetry.
        repeat(5) {
            val alerts = rulesEngine.evaluate(breachEvent(t0))
            for (alert in alerts) {
                router.route(alert, varRule().channels)
            }
        }

        val stored = inApp.getRecentAlerts(limit = 50)
        stored.size shouldBe 1
        stored[0].bookId shouldBe "derivatives-book"
        stored[0].ruleId shouldBe "dedup-rule-var"
    }

    test("a rule fires, the alert is resolved, then the same rule fires again WITHIN the suppression window — the second firing is suppressed") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        val t0 = Instant.parse("2026-05-28T14:00:00Z")
        var nowRef: Instant = t0
        // Mutable clock so we can advance "wall-clock time" between phases.
        val mutableClock = object : Clock() {
            override fun instant(): Instant = nowRef
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?): Clock = this
        }
        val rulesEngine = RulesEngine(
            ruleRepo,
            eventRepository = eventRepo,
            suppressionWindowSeconds = 300L,
            clock = mutableClock,
        )
        val inApp = InAppDeliveryService(eventRepo)
        val router = DeliveryRouter(listOf(inApp))

        rulesEngine.addRule(varRule())

        // Phase 1: first fire stores an alert.
        val firstBatch = rulesEngine.evaluate(breachEvent(t0))
        for (alert in firstBatch) router.route(alert, varRule().channels)
        inApp.getRecentAlerts(limit = 50).size shouldBe 1
        val firstAlertId = inApp.getRecentAlerts(limit = 50).first().id

        // Phase 2: operator resolves the alert mid-window.
        nowRef = t0.plusSeconds(30)
        eventRepo.updateStatus(
            id = firstAlertId,
            status = AlertStatus.RESOLVED,
            resolvedAt = nowRef,
            resolvedReason = "Manually resolved by trader-1",
        )

        // Phase 3: the same rule fires again at t0+120s — still inside the
        // 5-min window from the original triggeredAt.
        nowRef = t0.plusSeconds(120)
        val secondBatch = rulesEngine.evaluate(breachEvent(nowRef))
        for (alert in secondBatch) router.route(alert, varRule().channels)

        // Suppression: total alert count remains 1.
        inApp.getRecentAlerts(limit = 50).size shouldBe 1
    }

    test("a rule fires, the alert is resolved, then the same rule fires AFTER the suppression window elapses — a new alert is created") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        val t0 = Instant.parse("2026-05-28T14:00:00Z")
        var nowRef: Instant = t0
        val mutableClock = object : Clock() {
            override fun instant(): Instant = nowRef
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?): Clock = this
        }
        val rulesEngine = RulesEngine(
            ruleRepo,
            eventRepository = eventRepo,
            suppressionWindowSeconds = 300L,
            clock = mutableClock,
        )
        val inApp = InAppDeliveryService(eventRepo)
        val router = DeliveryRouter(listOf(inApp))

        rulesEngine.addRule(varRule())

        val firstBatch = rulesEngine.evaluate(breachEvent(t0))
        for (alert in firstBatch) router.route(alert, varRule().channels)
        val firstAlertId = inApp.getRecentAlerts(limit = 50).first().id

        nowRef = t0.plusSeconds(30)
        eventRepo.updateStatus(
            id = firstAlertId,
            status = AlertStatus.RESOLVED,
            resolvedAt = nowRef,
            resolvedReason = "Cleared",
        )

        // Advance past the window — t0 + 301s.
        nowRef = t0.plusSeconds(301)
        val secondBatch = rulesEngine.evaluate(breachEvent(nowRef))
        for (alert in secondBatch) router.route(alert, varRule().channels)

        inApp.getRecentAlerts(limit = 50).size shouldBe 2
    }
})
