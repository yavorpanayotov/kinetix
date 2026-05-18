package com.kinetix.gateway.client

import java.time.Instant

data class AlertRuleItem(
    val id: String,
    val name: String,
    val type: String,
    val threshold: Double,
    val operator: String,
    val severity: String,
    val channels: List<String>,
    val enabled: Boolean,
)

data class AlertEventItem(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val type: String,
    val severity: String,
    val message: String,
    val currentValue: Double,
    val threshold: Double,
    val bookId: String,
    val triggeredAt: Instant,
    val status: String = "TRIGGERED",
    val resolvedAt: Instant? = null,
    val resolvedReason: String? = null,
    val escalatedAt: Instant? = null,
    val escalatedTo: String? = null,
    val correlationId: String? = null,
    val suggestedAction: String? = null,
)

data class CreateAlertRuleParams(
    val name: String,
    val type: String,
    val threshold: Double,
    val operator: String,
    val severity: String,
    val channels: List<String>,
)

data class AcknowledgeAlertParams(
    val acknowledgedBy: String,
    val notes: String? = null,
)

data class EscalateAlertParams(
    val reason: String,
    val assignee: String? = null,
)

data class ResolveAlertParams(
    val resolutionText: String,
)

sealed class AlertActionResult {
    data class Ok(val alert: AlertEventItem) : AlertActionResult()
    data object NotFound : AlertActionResult()
    data class BadRequest(val message: String) : AlertActionResult()
    data class Conflict(val message: String) : AlertActionResult()
}

interface NotificationServiceClient {
    suspend fun listRules(): List<AlertRuleItem>
    suspend fun createRule(params: CreateAlertRuleParams): AlertRuleItem
    suspend fun deleteRule(ruleId: String): Boolean
    suspend fun listAlerts(limit: Int = 50, status: String? = null): List<AlertEventItem>
    suspend fun listEscalatedAlerts(): List<AlertEventItem>
    suspend fun acknowledgeAlert(alertId: String, params: AcknowledgeAlertParams): AlertEventItem?
    suspend fun escalateAlert(alertId: String, params: EscalateAlertParams): AlertActionResult
    suspend fun resolveAlert(alertId: String, params: ResolveAlertParams): AlertActionResult
    suspend fun getAlertContributors(alertId: String): String?
}
