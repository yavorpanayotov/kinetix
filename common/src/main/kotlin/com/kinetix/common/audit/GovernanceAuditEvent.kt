package com.kinetix.common.audit

import kotlinx.serialization.Serializable

@Serializable
data class GovernanceAuditEvent(
    val eventType: AuditEventType,
    val userId: String,
    val userRole: String,
    val modelName: String? = null,
    val scenarioId: String? = null,
    val limitId: String? = null,
    val submissionId: String? = null,
    val bookId: String? = null,
    val alertId: String? = null,
    val details: String? = null,
    val correlationId: String? = null,
)
