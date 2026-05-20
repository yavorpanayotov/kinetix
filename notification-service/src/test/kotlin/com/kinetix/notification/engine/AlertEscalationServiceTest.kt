package com.kinetix.notification.engine

import com.kinetix.common.audit.AuditEventType
import com.kinetix.notification.audit.GovernanceAuditPublisher
import com.kinetix.notification.delivery.DeliveryRouter
import com.kinetix.notification.model.AlertEvent
import com.kinetix.notification.model.AlertStatus
import com.kinetix.notification.model.AlertType
import com.kinetix.notification.model.DeliveryChannel
import com.kinetix.notification.model.Severity
import com.kinetix.notification.persistence.InMemoryAlertEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.MDC
import java.time.Instant

class AlertEscalationServiceTest : FunSpec({

    fun warningAlert(id: String, acknowledgedAt: Instant) = AlertEvent(
        id = id,
        ruleId = "r1",
        ruleName = "VaR Breach",
        type = AlertType.VAR_BREACH,
        severity = Severity.WARNING,
        message = "VaR exceeded threshold",
        currentValue = 150_000.0,
        threshold = 100_000.0,
        bookId = "book-1",
        triggeredAt = acknowledgedAt.minusSeconds(3600),
        status = AlertStatus.ACKNOWLEDGED,
        acknowledgedAt = acknowledgedAt,
    )

    fun criticalAlert(id: String, acknowledgedAt: Instant) = AlertEvent(
        id = id,
        ruleId = "r2",
        ruleName = "Critical VaR",
        type = AlertType.VAR_BREACH,
        severity = Severity.CRITICAL,
        message = "Critical VaR breach",
        currentValue = 250_000.0,
        threshold = 100_000.0,
        bookId = "book-1",
        triggeredAt = acknowledgedAt.minusSeconds(3600),
        status = AlertStatus.ACKNOWLEDGED,
        acknowledgedAt = acknowledgedAt,
    )

    test("escalates WARNING alert acknowledged beyond timeout — severity promoted to CRITICAL, escalated to risk-manager and CRO") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(31 * 60) // 31 minutes ago

        val alert = warningAlert("alert-1", acknowledgedAt)
        repo.save(alert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        val escalated = repo.findById("alert-1")!!
        escalated.status shouldBe AlertStatus.ESCALATED
        escalated.escalatedAt shouldNotBe null
        // WARNING is promoted to CRITICAL on escalation, so target is risk-manager,cro
        escalated.severity shouldBe Severity.CRITICAL
        escalated.escalatedTo shouldBe "risk-manager,cro"
    }

    test("escalates CRITICAL alert acknowledged beyond timeout to risk-manager and CRO") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(31 * 60)

        val alert = criticalAlert("alert-2", acknowledgedAt)
        repo.save(alert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        val escalated = repo.findById("alert-2")!!
        escalated.status shouldBe AlertStatus.ESCALATED
        escalated.escalatedTo shouldBe "risk-manager,cro"
    }

    test("does not escalate alert acknowledged within timeout") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(10 * 60) // only 10 minutes ago

        val alert = warningAlert("alert-3", acknowledgedAt)
        repo.save(alert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        val unchanged = repo.findById("alert-3")!!
        unchanged.status shouldBe AlertStatus.ACKNOWLEDGED
        unchanged.escalatedAt shouldBe null
    }

    test("does not escalate TRIGGERED alert (must be ACKNOWLEDGED first)") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")

        val triggeredAlert = AlertEvent(
            id = "alert-4",
            ruleId = "r1",
            ruleName = "VaR Breach",
            type = AlertType.VAR_BREACH,
            severity = Severity.CRITICAL,
            message = "breach",
            currentValue = 150_000.0,
            threshold = 100_000.0,
            bookId = "book-1",
            triggeredAt = now.minusSeconds(3600),
            status = AlertStatus.TRIGGERED,
        )
        repo.save(triggeredAlert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        val unchanged = repo.findById("alert-4")!!
        unchanged.status shouldBe AlertStatus.TRIGGERED
        unchanged.escalatedAt shouldBe null
    }

    test("does not re-escalate already ESCALATED alert") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(90 * 60)

        val alreadyEscalated = AlertEvent(
            id = "alert-5",
            ruleId = "r1",
            ruleName = "VaR Breach",
            type = AlertType.VAR_BREACH,
            severity = Severity.WARNING,
            message = "breach",
            currentValue = 150_000.0,
            threshold = 100_000.0,
            bookId = "book-1",
            triggeredAt = acknowledgedAt.minusSeconds(3600),
            status = AlertStatus.ESCALATED,
            acknowledgedAt = acknowledgedAt,
            escalatedAt = acknowledgedAt.plusSeconds(31 * 60),
            escalatedTo = "desk-head",
        )
        repo.save(alreadyEscalated)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        // still ESCALATED, escalatedAt unchanged
        val unchanged = repo.findById("alert-5")!!
        unchanged.status shouldBe AlertStatus.ESCALATED
        unchanged.escalatedAt shouldBe alreadyEscalated.escalatedAt
    }

    test("re-routes escalated alert via delivery router with promoted CRITICAL channels") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(31 * 60)

        val alert = warningAlert("alert-6", acknowledgedAt)
        repo.save(alert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        coVerify(exactly = 1) {
            router.route(any(), listOf(DeliveryChannel.EMAIL, DeliveryChannel.WEBHOOK, DeliveryChannel.PAGER_DUTY))
        }
    }

    test("publishes an ALERT_ESCALATED audit event when an alert is escalated") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val auditPublisher = mockk<GovernanceAuditPublisher>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(31 * 60)

        val alert = warningAlert("alert-audit", acknowledgedAt)
        repo.save(alert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30, auditPublisher = auditPublisher)
        service.processEscalations(now)

        verify(exactly = 1) {
            auditPublisher.publish(
                match { event ->
                    event.eventType == AuditEventType.ALERT_ESCALATED &&
                        event.bookId == "book-1" &&
                        event.details?.contains("alert-audit") == true &&
                        // WARNING is promoted to CRITICAL, so target is risk-manager,cro
                        event.details?.contains("risk-manager,cro") == true
                },
            )
        }
    }

    test("ALERT_ESCALATED governance event carries the correlationId from the MDC") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val auditPublisher = mockk<GovernanceAuditPublisher>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(31 * 60)

        val alert = warningAlert("alert-corr", acknowledgedAt)
        repo.save(alert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30, auditPublisher = auditPublisher)

        try {
            MDC.put("correlationId", "corr-alert-555")
            service.processEscalations(now)
        } finally {
            MDC.clear()
        }

        verify(exactly = 1) {
            auditPublisher.publish(
                match { event -> event.correlationId == "corr-alert-555" },
            )
        }
    }

    test("escalates WARNING alert via email, webhook, and PagerDuty after severity promotion to CRITICAL") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(31 * 60)

        val alert = warningAlert("alt04-warning", acknowledgedAt)
        repo.save(alert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        coVerify(exactly = 1) {
            router.route(any(), listOf(DeliveryChannel.EMAIL, DeliveryChannel.WEBHOOK, DeliveryChannel.PAGER_DUTY))
        }
    }

    test("escalates CRITICAL alert via email, webhook, and PagerDuty") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(31 * 60)

        val alert = criticalAlert("alt04-critical", acknowledgedAt)
        repo.save(alert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        coVerify(exactly = 1) {
            router.route(any(), listOf(DeliveryChannel.EMAIL, DeliveryChannel.WEBHOOK, DeliveryChannel.PAGER_DUTY))
        }
    }

    test("resolved alert is not escalated even if acknowledged long ago") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(60 * 60)

        val resolvedAlert = AlertEvent(
            id = "alert-7",
            ruleId = "r1",
            ruleName = "VaR Breach",
            type = AlertType.VAR_BREACH,
            severity = Severity.WARNING,
            message = "breach",
            currentValue = 150_000.0,
            threshold = 100_000.0,
            bookId = "book-1",
            triggeredAt = acknowledgedAt.minusSeconds(3600),
            status = AlertStatus.RESOLVED,
            acknowledgedAt = acknowledgedAt,
            resolvedAt = acknowledgedAt.plusSeconds(600),
            resolvedReason = "MANUAL",
        )
        repo.save(resolvedAlert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        val unchanged = repo.findById("alert-7")!!
        unchanged.status shouldBe AlertStatus.RESOLVED
    }

    // ALT-05: severity promotion rules
    test("promoteSeverity promotes WARNING to CRITICAL") {
        val service = AlertEscalationService(
            repository = InMemoryAlertEventRepository(),
            deliveryRouter = mockk(relaxed = true),
        )
        service.promoteSeverity(Severity.WARNING) shouldBe Severity.CRITICAL
    }

    test("promoteSeverity leaves CRITICAL unchanged") {
        val service = AlertEscalationService(
            repository = InMemoryAlertEventRepository(),
            deliveryRouter = mockk(relaxed = true),
        )
        service.promoteSeverity(Severity.CRITICAL) shouldBe Severity.CRITICAL
    }

    test("promoteSeverity leaves INFO unchanged") {
        val service = AlertEscalationService(
            repository = InMemoryAlertEventRepository(),
            deliveryRouter = mockk(relaxed = true),
        )
        service.promoteSeverity(Severity.INFO) shouldBe Severity.INFO
    }

    test("escalated WARNING alert has severity promoted to CRITICAL in repository") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(31 * 60)

        val alert = warningAlert("alt05-promote", acknowledgedAt)
        repo.save(alert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        val escalated = repo.findById("alt05-promote")!!
        escalated.severity shouldBe Severity.CRITICAL
    }

    test("escalated INFO alert severity stays INFO") {
        val repo = InMemoryAlertEventRepository()
        val router = mockk<DeliveryRouter>(relaxed = true)
        val now = Instant.parse("2025-01-15T12:00:00Z")
        val acknowledgedAt = now.minusSeconds(31 * 60)

        val infoAlert = AlertEvent(
            id = "alt05-info",
            ruleId = "r3",
            ruleName = "Data Staleness",
            type = AlertType.DATA_STALENESS,
            severity = Severity.INFO,
            message = "stale data",
            currentValue = 0.0,
            threshold = 0.0,
            bookId = "book-1",
            triggeredAt = acknowledgedAt.minusSeconds(3600),
            status = AlertStatus.ACKNOWLEDGED,
            acknowledgedAt = acknowledgedAt,
        )
        repo.save(infoAlert)

        val service = AlertEscalationService(repo, router, escalationTimeoutMinutes = 30)
        service.processEscalations(now)

        val escalated = repo.findById("alt05-info")!!
        escalated.severity shouldBe Severity.INFO
    }
})
