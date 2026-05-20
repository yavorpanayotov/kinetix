package com.kinetix.notification.engine

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.notification.audit.GovernanceAuditPublisher
import com.kinetix.notification.delivery.DeliveryRouter
import com.kinetix.notification.model.DeliveryChannel
import com.kinetix.notification.model.Severity
import com.kinetix.notification.persistence.AlertEventRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant

class AlertEscalationService(
    private val repository: AlertEventRepository,
    private val deliveryRouter: DeliveryRouter,
    private val escalationTimeoutMinutes: Long = 30,
    private val auditPublisher: GovernanceAuditPublisher? = null,
) {
    private val logger = LoggerFactory.getLogger(AlertEscalationService::class.java)

    suspend fun processEscalations(now: Instant = Instant.now()) {
        val cutoff = now.minusSeconds(escalationTimeoutMinutes * 60)
        val overdue = repository.findAcknowledgedBefore(cutoff)

        for (alert in overdue) {
            val promotedSeverity = promoteSeverity(alert.severity)
            val escalatedTo = escalationTargetFor(promotedSeverity)
            repository.escalate(alert.id, now, escalatedTo, promotedSeverity.takeIf { it != alert.severity })

            val escalatedAlert = repository.findById(alert.id) ?: continue
            val channels = escalationChannelsFor(promotedSeverity)
            deliveryRouter.route(escalatedAlert, channels)

            auditPublisher?.publish(
                GovernanceAuditEvent(
                    eventType = AuditEventType.ALERT_ESCALATED,
                    userId = "system",
                    userRole = "SYSTEM",
                    bookId = alert.bookId,
                    details = "alertId=${alert.id} severity=${alert.severity}->${promotedSeverity} escalatedTo=$escalatedTo type=${alert.type}",
                    correlationId = MDC.get("correlationId"),
                ),
            )

            logger.info(
                "Escalated alert={} severity={}=>{} to={} after {}min timeout",
                alert.id, alert.severity, promotedSeverity, escalatedTo, escalationTimeoutMinutes,
            )
        }
    }

    /**
     * Promotes severity on escalation: WARNING -> CRITICAL.
     * INFO stays INFO (informational alerts are not promoted).
     * CRITICAL stays CRITICAL.
     */
    internal fun promoteSeverity(severity: Severity): Severity = when (severity) {
        Severity.WARNING -> Severity.CRITICAL
        Severity.CRITICAL, Severity.INFO -> severity
    }

    private fun escalationTargetFor(severity: Severity): String = when (severity) {
        Severity.WARNING -> "desk-head"
        Severity.CRITICAL -> "risk-manager,cro"
        Severity.INFO -> "desk-head"
    }

    /**
     * Routes escalations across channels based on severity:
     * - WARNING  -> email only
     * - CRITICAL -> email + webhook + PagerDuty
     * - INFO     -> email only
     */
    private fun escalationChannelsFor(severity: Severity): List<DeliveryChannel> = when (severity) {
        Severity.CRITICAL -> listOf(DeliveryChannel.EMAIL, DeliveryChannel.WEBHOOK, DeliveryChannel.PAGER_DUTY)
        Severity.WARNING, Severity.INFO -> listOf(DeliveryChannel.EMAIL)
    }
}
