package com.kinetix.notification.routes.dtos

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/notifications/alerts/{id}/snooze`.
 *
 * Sets a forward-looking silencer on an alert: while `snoozedUntil > now`, the
 * RulesEngine skips re-firing the alert's rule for its book. Snoozing does not
 * change the alert's lifecycle status — a TRIGGERED alert stays TRIGGERED.
 *
 * @param snoozedUntil ISO-8601 timestamp in the future at which the snooze
 *                     expires. Must be strictly later than the current time.
 */
@Serializable
data class SnoozeAlertRequest(
    val snoozedUntil: String,
)
