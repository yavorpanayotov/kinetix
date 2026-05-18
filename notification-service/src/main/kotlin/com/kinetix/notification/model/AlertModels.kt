package com.kinetix.notification.model

import java.time.Instant

enum class AlertType {
    VAR_BREACH,
    PNL_THRESHOLD,
    RISK_LIMIT,
    DELTA_BREACH,
    VEGA_BREACH,
    CONCENTRATION,
    MARGIN_BREACH,
    DATA_STALENESS,
    LIQUIDITY_CONCENTRATION,
    REGIME_CHANGE,
    FACTOR_CONCENTRATION,
    LIMIT_BREACH,
    RISK_BUDGET_EXCEEDED,
}
enum class AlertStatus {
    TRIGGERED, ACKNOWLEDGED, ESCALATED, RESOLVED;

    fun canTransitionTo(next: AlertStatus): Boolean = when (this) {
        TRIGGERED -> next == ACKNOWLEDGED || next == ESCALATED || next == RESOLVED
        ACKNOWLEDGED -> next == ESCALATED || next == RESOLVED
        ESCALATED -> next == RESOLVED
        RESOLVED -> false
    }
}
enum class Severity { INFO, WARNING, CRITICAL }
enum class ComparisonOperator { GREATER_THAN, LESS_THAN, EQUALS }
enum class DeliveryChannel { IN_APP, EMAIL, WEBHOOK, PAGER_DUTY }

data class AlertRule(
    val id: String,
    val name: String,
    val type: AlertType,
    val threshold: Double,
    val operator: ComparisonOperator,
    val severity: Severity,
    val channels: List<DeliveryChannel>,
    val enabled: Boolean = true,
)

data class AlertEvent(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val type: AlertType,
    val severity: Severity,
    val message: String,
    val currentValue: Double,
    val threshold: Double,
    val bookId: String,
    val triggeredAt: Instant,
    val status: AlertStatus = AlertStatus.TRIGGERED,
    val acknowledgedAt: Instant? = null,
    val resolvedAt: Instant? = null,
    val resolvedReason: String? = null,
    val escalatedAt: Instant? = null,
    val escalatedTo: String? = null,
    val correlationId: String? = null,
    val contributors: String? = null,
    val suggestedAction: String? = null,
    val snoozedUntil: Instant? = null,
)
