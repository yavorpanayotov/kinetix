package com.kinetix.notification.persistence

import com.kinetix.notification.model.AlertEvent
import com.kinetix.notification.model.AlertStatus
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

class InMemoryAlertEventRepository : AlertEventRepository {
    private val events = ConcurrentLinkedDeque<AlertEvent>()

    override suspend fun save(event: AlertEvent) {
        events.addFirst(event)
    }

    override suspend fun findRecent(limit: Int, status: AlertStatus?): List<AlertEvent> {
        val filtered = if (status != null) events.filter { it.status == status } else events.toList()
        return filtered.take(limit)
    }

    override suspend fun findActiveByRuleAndBook(ruleId: String, bookId: String): AlertEvent? =
        events.find { it.ruleId == ruleId && it.bookId == bookId && it.status == AlertStatus.TRIGGERED }

    override suspend fun findLatestByRuleAndBook(ruleId: String, bookId: String): AlertEvent? =
        events.filter { it.ruleId == ruleId && it.bookId == bookId }
            .maxByOrNull { it.triggeredAt }

    override suspend fun findActiveByBook(bookId: String): List<AlertEvent> =
        events.filter { it.bookId == bookId && it.status == AlertStatus.TRIGGERED }

    override suspend fun updateStatus(
        id: String,
        status: AlertStatus,
        resolvedAt: Instant?,
        resolvedReason: String?,
    ) {
        val event = events.find { it.id == id } ?: return
        events.remove(event)
        events.addFirst(event.copy(status = status, resolvedAt = resolvedAt, resolvedReason = resolvedReason))
    }

    override suspend fun acknowledge(id: String, acknowledgedAt: Instant) {
        val event = events.find { it.id == id } ?: return
        events.remove(event)
        events.addFirst(event.copy(status = AlertStatus.ACKNOWLEDGED, acknowledgedAt = acknowledgedAt))
    }

    override suspend fun escalate(
        id: String,
        escalatedAt: Instant,
        escalatedTo: String,
        promotedSeverity: com.kinetix.notification.model.Severity?,
    ) {
        val event = events.find { it.id == id } ?: return
        events.remove(event)
        events.addFirst(
            event.copy(
                status = AlertStatus.ESCALATED,
                escalatedAt = escalatedAt,
                escalatedTo = escalatedTo,
                severity = promotedSeverity ?: event.severity,
            )
        )
    }

    override suspend fun findAcknowledgedBefore(cutoff: Instant): List<AlertEvent> =
        events.filter { it.status == AlertStatus.ACKNOWLEDGED && it.acknowledgedAt != null && it.acknowledgedAt < cutoff }

    override suspend fun findById(id: String): AlertEvent? = events.find { it.id == id }

    override suspend fun snooze(id: String, until: Instant): AlertEvent? {
        val event = events.find { it.id == id } ?: return null
        val updated = event.copy(snoozedUntil = until)
        events.remove(event)
        events.addFirst(updated)
        return updated
    }
}
