package com.kinetix.gateway.dtos

import com.kinetix.gateway.client.AlertEventItem
import kotlinx.serialization.Serializable

@Serializable
data class AlertEventDto(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val type: String,
    val severity: String,
    val message: String,
    val currentValue: Double,
    val threshold: Double,
    val bookId: String,
    val triggeredAt: String,
    val status: String = "TRIGGERED",
    val resolvedAt: String? = null,
    val resolvedReason: String? = null,
    val escalatedAt: String? = null,
    val escalatedTo: String? = null,
    val correlationId: String? = null,
    val suggestedAction: String? = null,
    val snoozedUntil: String? = null,
)

fun AlertEventItem.toDto(): AlertEventDto = AlertEventDto(
    id = id,
    ruleId = ruleId,
    ruleName = ruleName,
    type = type,
    severity = severity,
    message = message,
    currentValue = currentValue,
    threshold = threshold,
    bookId = bookId,
    triggeredAt = triggeredAt.toString(),
    status = status,
    resolvedAt = resolvedAt?.toString(),
    resolvedReason = resolvedReason,
    escalatedAt = escalatedAt?.toString(),
    escalatedTo = escalatedTo,
    correlationId = correlationId,
    suggestedAction = suggestedAction,
    snoozedUntil = snoozedUntil?.toString(),
)
