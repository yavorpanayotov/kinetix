package com.kinetix.notification.engine

import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.notification.model.*
import com.kinetix.notification.persistence.InMemoryAlertEventRepository
import com.kinetix.notification.persistence.InMemoryAlertRuleRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class AlertLifecycleTest : FunSpec({

    fun createEngine(
        suppressionWindowSeconds: Long = DEFAULT_ALERT_SUPPRESSION_WINDOW_SECONDS,
    ): Triple<RulesEngine, InMemoryAlertRuleRepository, InMemoryAlertEventRepository> {
        val ruleRepo = InMemoryAlertRuleRepository()
        val eventRepo = InMemoryAlertEventRepository()
        val engine = RulesEngine(
            ruleRepo,
            eventRepository = eventRepo,
            suppressionWindowSeconds = suppressionWindowSeconds,
        )
        return Triple(engine, ruleRepo, eventRepo)
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

    test("deduplication suppresses duplicate alert for same rule and book") {
        val (engine, _, eventRepo) = createEngine()
        engine.addRule(varBreachRule())

        val firstAlerts = engine.evaluate(riskEvent())
        firstAlerts shouldHaveSize 1
        eventRepo.save(firstAlerts[0])

        val secondAlerts = engine.evaluate(riskEvent(varValue = "160000.0"))
        secondAlerts.shouldBeEmpty()
    }

    test("deduplication allows alert after previous one is resolved (outside suppression window)") {
        // Disable the sliding-window suppression so this test focuses on the
        // status-based dedup gate: once a prior alert is RESOLVED, a fresh
        // breach is allowed through. The 5-min suppression window is a
        // separate guarantee covered by AlertDedupAcceptanceTest.
        val (engine, _, eventRepo) = createEngine(suppressionWindowSeconds = 0L)
        engine.addRule(varBreachRule())

        val firstAlerts = engine.evaluate(riskEvent())
        firstAlerts shouldHaveSize 1
        eventRepo.save(firstAlerts[0])

        eventRepo.updateStatus(firstAlerts[0].id, AlertStatus.RESOLVED)

        val secondAlerts = engine.evaluate(riskEvent(varValue = "170000.0"))
        secondAlerts shouldHaveSize 1
        secondAlerts[0].id shouldNotBe firstAlerts[0].id
    }

    test("deduplication allows alerts for different books") {
        val (engine, _, eventRepo) = createEngine()
        engine.addRule(varBreachRule())

        val bookOneAlerts = engine.evaluate(riskEvent(bookId = "book-1"))
        bookOneAlerts shouldHaveSize 1
        eventRepo.save(bookOneAlerts[0])

        val bookTwoAlerts = engine.evaluate(riskEvent(bookId = "book-2"))
        bookTwoAlerts shouldHaveSize 1
    }

    test("auto-resolve transitions TRIGGERED alert to RESOLVED when metric clears") {
        val (engine, _, eventRepo) = createEngine()
        engine.addRule(varBreachRule())

        val alerts = engine.evaluate(riskEvent(varValue = "150000.0"))
        alerts shouldHaveSize 1
        eventRepo.save(alerts[0])

        // VaR drops below threshold
        engine.evaluate(riskEvent(varValue = "50000.0"))

        val resolved = eventRepo.findById(alerts[0].id)
        resolved shouldNotBe null
        resolved!!.status shouldBe AlertStatus.RESOLVED
        resolved.resolvedReason shouldBe "AUTO_CLEARED"
        resolved.resolvedAt shouldNotBe null
    }

    test("auto-resolve does not affect alerts for different rules") {
        val (engine, _, eventRepo) = createEngine()
        engine.addRule(varBreachRule("r1"))
        engine.addRule(
            AlertRule(
                id = "r2", name = "ES Warning", type = AlertType.PNL_THRESHOLD,
                threshold = 190_000.0, operator = ComparisonOperator.GREATER_THAN,
                severity = Severity.WARNING, channels = listOf(DeliveryChannel.IN_APP),
            ),
        )

        // Trigger only VaR alert (ES=180000 < PNL threshold=190000)
        val event1 = riskEvent(varValue = "150000.0")
        val alerts1 = engine.evaluate(event1)
        alerts1 shouldHaveSize 1
        alerts1[0].type shouldBe AlertType.VAR_BREACH
        eventRepo.save(alerts1[0])

        // VaR drops but ES now fires — VaR alert should be auto-resolved
        val event2 = RiskResultEvent(
            bookId = "book-1",
            varValue = "50000.0",
            expectedShortfall = "200000.0",
            calculationType = "PARAMETRIC",
            calculatedAt = "2025-01-15T10:05:00Z",
        )
        val alerts2 = engine.evaluate(event2)
        alerts2 shouldHaveSize 1
        alerts2[0].type shouldBe AlertType.PNL_THRESHOLD

        val varAlert = eventRepo.findById(alerts1[0].id)
        varAlert!!.status shouldBe AlertStatus.RESOLVED
    }

    test("oscillating VaR creates two separate alert events (outside suppression window)") {
        // Disable the sliding-window suppression so this test focuses purely
        // on the breach→clear→breach oscillation pattern. With the window
        // active the second breach is intentionally suppressed; that path is
        // covered by AlertDedupAcceptanceTest.
        val (engine, _, eventRepo) = createEngine(suppressionWindowSeconds = 0L)
        engine.addRule(varBreachRule())

        // First breach
        val firstAlerts = engine.evaluate(riskEvent(varValue = "150000.0"))
        firstAlerts shouldHaveSize 1
        eventRepo.save(firstAlerts[0])

        // Clear — auto-resolves
        engine.evaluate(riskEvent(varValue = "50000.0"))
        val afterClear = eventRepo.findById(firstAlerts[0].id)
        afterClear!!.status shouldBe AlertStatus.RESOLVED

        // Second breach — new alert
        val secondAlerts = engine.evaluate(riskEvent(varValue = "160000.0"))
        secondAlerts shouldHaveSize 1
        secondAlerts[0].id shouldNotBe firstAlerts[0].id
    }

    test("new alert includes correlationId from risk event") {
        val (engine, _, _) = createEngine()
        engine.addRule(varBreachRule())

        val event = RiskResultEvent(
            bookId = "book-1",
            varValue = "150000.0",
            expectedShortfall = "180000.0",
            calculationType = "PARAMETRIC",
            calculatedAt = "2025-01-15T10:00:00Z",
            correlationId = "corr-123",
        )
        val alerts = engine.evaluate(event)
        alerts shouldHaveSize 1
        alerts[0].correlationId shouldBe "corr-123"
    }

    test("status filter on findRecent returns only matching alerts") {
        val eventRepo = InMemoryAlertEventRepository()
        val triggered = AlertEvent(
            id = "e1", ruleId = "r1", ruleName = "VaR", type = AlertType.VAR_BREACH,
            severity = Severity.CRITICAL, message = "breach", currentValue = 150_000.0,
            threshold = 100_000.0, bookId = "book-1",
            triggeredAt = java.time.Instant.now(),
            status = AlertStatus.TRIGGERED,
        )
        val resolved = AlertEvent(
            id = "e2", ruleId = "r1", ruleName = "VaR", type = AlertType.VAR_BREACH,
            severity = Severity.CRITICAL, message = "resolved", currentValue = 50_000.0,
            threshold = 100_000.0, bookId = "book-1",
            triggeredAt = java.time.Instant.now(),
            status = AlertStatus.RESOLVED,
            resolvedAt = java.time.Instant.now(),
            resolvedReason = "AUTO_CLEARED",
        )
        eventRepo.save(triggered)
        eventRepo.save(resolved)

        val active = eventRepo.findRecent(50, AlertStatus.TRIGGERED)
        active shouldHaveSize 1
        active[0].id shouldBe "e1"

        val all = eventRepo.findRecent(50)
        all shouldHaveSize 2
    }
})
