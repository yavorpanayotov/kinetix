package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/notifications/alerts/{id}/snooze` (gateway proxy).
 *
 * @param snoozedUntil ISO-8601 timestamp in the future at which the snooze
 *                     expires. Validation is performed by the upstream
 *                     notification-service.
 */
@Serializable
data class SnoozeAlertRequest(
    val snoozedUntil: String,
)
