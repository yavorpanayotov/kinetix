package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/notifications/alerts/{id}/escalate`.
 *
 * @param reason Free-text justification for manual escalation. Required, non-blank.
 * @param assignee Optional escalation target. Defaults to a severity-based assignee
 *                 when omitted.
 */
@Serializable
data class EscalateAlertRequest(
    val reason: String,
    val assignee: String? = null,
)
