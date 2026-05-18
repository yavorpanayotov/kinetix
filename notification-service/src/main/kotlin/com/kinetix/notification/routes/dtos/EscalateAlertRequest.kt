package com.kinetix.notification.routes.dtos

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/notifications/alerts/{id}/escalate`.
 *
 * @param reason Free-text justification for the manual escalation. Required, non-blank.
 * @param assignee Optional target for the escalation (e.g. `risk-manager`, `desk-head`).
 *                 When omitted, the route handler picks a default based on the alert severity.
 */
@Serializable
data class EscalateAlertRequest(
    val reason: String,
    val assignee: String? = null,
)
