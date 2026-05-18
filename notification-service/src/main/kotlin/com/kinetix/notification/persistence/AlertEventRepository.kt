package com.kinetix.notification.persistence

import com.kinetix.notification.model.AlertEvent
import com.kinetix.notification.model.AlertStatus

interface AlertEventRepository {
    suspend fun save(event: AlertEvent)
    suspend fun findRecent(limit: Int = 50, status: AlertStatus? = null): List<AlertEvent>
    suspend fun findActiveByRuleAndBook(ruleId: String, bookId: String): AlertEvent?

    /**
     * Most recent alert event for a (rule, book) pair, regardless of status. Used by the
     * evaluator's "skip if snoozed" guard so that even a RESOLVED or ESCALATED alert can
     * carry a forward-looking snooze window that suppresses re-firing.
     */
    suspend fun findLatestByRuleAndBook(ruleId: String, bookId: String): AlertEvent?
    suspend fun findActiveByBook(bookId: String): List<AlertEvent>
    suspend fun updateStatus(id: String, status: AlertStatus, resolvedAt: java.time.Instant? = null, resolvedReason: String? = null)
    suspend fun acknowledge(id: String, acknowledgedAt: java.time.Instant)
    suspend fun escalate(
        id: String,
        escalatedAt: java.time.Instant,
        escalatedTo: String,
        promotedSeverity: com.kinetix.notification.model.Severity? = null,
    )
    suspend fun findAcknowledgedBefore(cutoff: java.time.Instant): List<AlertEvent>
    suspend fun findById(id: String): AlertEvent?

    /**
     * Set a snooze window on an alert. While `snoozed_until > now()` the
     * evaluator skips re-firing the alert's rule for its book. The alert's
     * lifecycle status is not changed by this call.
     *
     * @return updated [AlertEvent] or `null` when no row matched the id.
     */
    suspend fun snooze(id: String, until: java.time.Instant): AlertEvent?
}
