package com.kinetix.notification.model

/**
 * Operator-tier triage classification driving alert escalation routing.
 *
 * The existing service-level [Severity] (INFO/WARNING/CRITICAL) describes
 * the kind of upstream event ("a numeric breach occurred", "a curve went
 * stale"). [AlertSeverity] is the operator-side decision about who needs
 * to know and through which channel: CRITICAL pages the on-call officer,
 * HIGH posts to a Slack room and emails the desk, MEDIUM emails only,
 * LOW just flips a UI badge. The two enums coexist because their
 * lifecycles are independent — the upstream Severity can change with the
 * event, while the AlertSeverity is set by the routing rule.
 *
 * Escalation strictly increases with tier: every channel reachable at
 * tier N is also reachable at tier N+1, plus at least one more. This is
 * an invariant the [SeverityEscalationRoutingTest] pins down.
 */
enum class AlertSeverity(val tier: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    /** Channels this severity routes through, ordered from highest to lowest urgency. */
    fun escalationChannels(): List<DeliveryChannel> = when (this) {
        CRITICAL -> listOf(
            DeliveryChannel.PAGER_DUTY,
            DeliveryChannel.WEBHOOK,
            DeliveryChannel.EMAIL,
            DeliveryChannel.IN_APP,
        )
        HIGH -> listOf(
            DeliveryChannel.WEBHOOK,
            DeliveryChannel.EMAIL,
            DeliveryChannel.IN_APP,
        )
        MEDIUM -> listOf(DeliveryChannel.EMAIL, DeliveryChannel.IN_APP)
        LOW -> listOf(DeliveryChannel.IN_APP)
    }

    companion object {
        /** Map the legacy three-tier service-level severity into a routing tier. */
        fun fromServiceSeverity(severity: Severity): AlertSeverity = when (severity) {
            Severity.CRITICAL -> CRITICAL
            Severity.WARNING -> MEDIUM
            Severity.INFO -> LOW
        }
    }
}
