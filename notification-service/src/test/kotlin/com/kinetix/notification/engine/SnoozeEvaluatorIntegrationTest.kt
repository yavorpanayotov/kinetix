package com.kinetix.notification.engine

import com.kinetix.common.kafka.events.RiskResultEvent
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Integration test for the RulesEngine "skip if snoozed" guard. Exercises the
 * real Flyway-migrated postgres schema (snoozed_until column) and the real
 * Exposed repository so we know the wiring works end-to-end.
 */
class SnoozeEvaluatorIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()

    beforeEach {
        transaction(db) {
            AlertAcknowledgementsTable.deleteAll()
            AlertEventsTable.deleteAll()
            AlertRulesTable.deleteAll()
        }
    }

    fun varBreachRule(id: String = "r1") = AlertRule(
        id = id, name = "VaR Limit", type = AlertType.VAR_BREACH,
        threshold = 100_000.0, operator = ComparisonOperator.GREATER_THAN,
        severity = Severity.CRITICAL, channels = listOf(DeliveryChannel.IN_APP),
    )

    fun riskEvent(varValue: String = "150000.0", bookId: String = "book-1") =
        RiskResultEvent(
            bookId = bookId,
            varValue = varValue,
            expectedShortfall = "180000.0",
            calculationType = "PARAMETRIC",
            calculatedAt = "2025-01-15T10:00:00Z",
        )

    test("RulesEngine skips firing a rule while the latest alert for (rule, book) is snoozed in the future") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        val engine = RulesEngine(ruleRepo, eventRepository = eventRepo)
        engine.addRule(varBreachRule())

        // First breach fires
        val firstAlerts = engine.evaluate(riskEvent())
        firstAlerts shouldHaveSize 1
        eventRepo.save(firstAlerts[0])

        // User snoozes the alert for 1 hour into the future
        val until = Instant.now().plus(1, ChronoUnit.HOURS)
        eventRepo.snooze(firstAlerts[0].id, until)

        // Even after resolving (so dedup wouldn't catch it), the snooze still suppresses re-fire
        eventRepo.updateStatus(firstAlerts[0].id, AlertStatus.RESOLVED, resolvedAt = Instant.now(), resolvedReason = "user")

        // A subsequent breach for the same (rule, book) is suppressed by the snooze guard
        val secondAlerts = engine.evaluate(riskEvent(varValue = "160000.0"))
        secondAlerts.shouldBeEmpty()
    }

    test("RulesEngine re-fires once the snooze window has elapsed") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        val engine = RulesEngine(ruleRepo, eventRepository = eventRepo)
        engine.addRule(varBreachRule())

        val firstAlerts = engine.evaluate(riskEvent())
        firstAlerts shouldHaveSize 1
        eventRepo.save(firstAlerts[0])

        // Snooze for a very short window, then wait it out
        val until = Instant.now().plus(2, ChronoUnit.SECONDS)
        eventRepo.snooze(firstAlerts[0].id, until)
        // Resolve so dedup doesn't suppress instead of snooze
        eventRepo.updateStatus(firstAlerts[0].id, AlertStatus.RESOLVED, resolvedAt = Instant.now(), resolvedReason = "user")

        // While snoozed → suppressed
        engine.evaluate(riskEvent(varValue = "160000.0")).shouldBeEmpty()

        // Wait for snooze to expire (3s > 2s window)
        Thread.sleep(3_000)

        // After expiry the rule re-fires
        val laterAlerts = engine.evaluate(riskEvent(varValue = "170000.0"))
        laterAlerts shouldHaveSize 1
    }

    test("snooze applies per (rule, book) — another book continues to fire normally") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        val engine = RulesEngine(ruleRepo, eventRepository = eventRepo)
        engine.addRule(varBreachRule())

        // Book-1 fires and is snoozed
        val bookOne = engine.evaluate(riskEvent(bookId = "book-1"))
        bookOne shouldHaveSize 1
        eventRepo.save(bookOne[0])
        eventRepo.snooze(bookOne[0].id, Instant.now().plus(1, ChronoUnit.HOURS))
        eventRepo.updateStatus(bookOne[0].id, AlertStatus.RESOLVED, resolvedAt = Instant.now(), resolvedReason = "user")

        // Book-2 has its own breach — snooze on book-1 does not silence book-2
        val bookTwo = engine.evaluate(riskEvent(bookId = "book-2"))
        bookTwo shouldHaveSize 1
        bookTwo[0].bookId shouldBe "book-2"
    }
})
