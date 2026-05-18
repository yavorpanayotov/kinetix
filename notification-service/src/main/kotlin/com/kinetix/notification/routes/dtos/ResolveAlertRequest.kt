package com.kinetix.notification.routes.dtos

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/notifications/alerts/{id}/resolve`.
 *
 * @param resolutionText Free-text description of how the alert was resolved. Required, non-blank.
 *                       Stored in `alert_events.resolved_reason`.
 */
@Serializable
data class ResolveAlertRequest(
    val resolutionText: String,
)
