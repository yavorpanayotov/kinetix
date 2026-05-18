package com.kinetix.notification.persistence

import com.kinetix.notification.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedAlertEventRepository(private val db: Database? = null) : AlertEventRepository {

    override suspend fun save(event: AlertEvent): Unit = newSuspendedTransaction(db = db) {
        AlertEventsTable.insert {
            it[id] = event.id
            it[ruleId] = event.ruleId
            it[ruleName] = event.ruleName
            it[type] = event.type.name
            it[severity] = event.severity.name
            it[message] = event.message
            it[currentValue] = event.currentValue.toBigDecimal()
            it[threshold] = event.threshold.toBigDecimal()
            it[bookId] = event.bookId
            it[triggeredAt] = OffsetDateTime.ofInstant(event.triggeredAt, ZoneOffset.UTC)
            it[status] = event.status.name
            it[acknowledgedAt] = event.acknowledgedAt?.let { ts -> OffsetDateTime.ofInstant(ts, ZoneOffset.UTC) }
            it[resolvedAt] = event.resolvedAt?.let { ts -> OffsetDateTime.ofInstant(ts, ZoneOffset.UTC) }
            it[resolvedReason] = event.resolvedReason
            it[escalatedAt] = event.escalatedAt?.let { ts -> OffsetDateTime.ofInstant(ts, ZoneOffset.UTC) }
            it[escalatedTo] = event.escalatedTo
            it[correlationId] = event.correlationId
            it[contributors] = event.contributors
            it[suggestedAction] = event.suggestedAction
            it[snoozedUntil] = event.snoozedUntil?.let { ts -> OffsetDateTime.ofInstant(ts, ZoneOffset.UTC) }
        }
    }

    override suspend fun findRecent(limit: Int, status: AlertStatus?): List<AlertEvent> =
        newSuspendedTransaction(db = db) {
            val query = AlertEventsTable.selectAll()
            if (status != null) {
                query.andWhere { AlertEventsTable.status eq status.name }
            }
            query
                .orderBy(AlertEventsTable.triggeredAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toAlertEvent() }
        }

    override suspend fun findActiveByRuleAndBook(ruleId: String, bookId: String): AlertEvent? =
        newSuspendedTransaction(db = db) {
            AlertEventsTable
                .selectAll()
                .where {
                    (AlertEventsTable.ruleId eq ruleId) and
                        (AlertEventsTable.bookId eq bookId) and
                        (AlertEventsTable.status eq AlertStatus.TRIGGERED.name)
                }
                .orderBy(AlertEventsTable.triggeredAt, SortOrder.DESC)
                .limit(1)
                .map { it.toAlertEvent() }
                .firstOrNull()
        }

    override suspend fun findLatestByRuleAndBook(ruleId: String, bookId: String): AlertEvent? =
        newSuspendedTransaction(db = db) {
            AlertEventsTable
                .selectAll()
                .where {
                    (AlertEventsTable.ruleId eq ruleId) and
                        (AlertEventsTable.bookId eq bookId)
                }
                .orderBy(AlertEventsTable.triggeredAt, SortOrder.DESC)
                .limit(1)
                .map { it.toAlertEvent() }
                .firstOrNull()
        }

    override suspend fun findActiveByBook(bookId: String): List<AlertEvent> =
        newSuspendedTransaction(db = db) {
            AlertEventsTable
                .selectAll()
                .where {
                    (AlertEventsTable.bookId eq bookId) and
                        (AlertEventsTable.status eq AlertStatus.TRIGGERED.name)
                }
                .map { it.toAlertEvent() }
        }

    override suspend fun updateStatus(
        id: String,
        status: AlertStatus,
        resolvedAt: Instant?,
        resolvedReason: String?,
    ): Unit = newSuspendedTransaction(db = db) {
        AlertEventsTable.update({ AlertEventsTable.id eq id }) {
            it[AlertEventsTable.status] = status.name
            if (resolvedAt != null) {
                it[AlertEventsTable.resolvedAt] = OffsetDateTime.ofInstant(resolvedAt, ZoneOffset.UTC)
            }
            if (resolvedReason != null) {
                it[AlertEventsTable.resolvedReason] = resolvedReason
            }
        }
    }

    override suspend fun acknowledge(
        id: String,
        acknowledgedAt: Instant,
    ): Unit = newSuspendedTransaction(db = db) {
        AlertEventsTable.update({ AlertEventsTable.id eq id }) {
            it[AlertEventsTable.status] = AlertStatus.ACKNOWLEDGED.name
            it[AlertEventsTable.acknowledgedAt] = OffsetDateTime.ofInstant(acknowledgedAt, ZoneOffset.UTC)
        }
    }

    override suspend fun escalate(
        id: String,
        escalatedAt: Instant,
        escalatedTo: String,
        promotedSeverity: com.kinetix.notification.model.Severity?,
    ): Unit = newSuspendedTransaction(db = db) {
        AlertEventsTable.update({ AlertEventsTable.id eq id }) {
            it[AlertEventsTable.status] = AlertStatus.ESCALATED.name
            it[AlertEventsTable.escalatedAt] = OffsetDateTime.ofInstant(escalatedAt, ZoneOffset.UTC)
            it[AlertEventsTable.escalatedTo] = escalatedTo
            if (promotedSeverity != null) {
                it[AlertEventsTable.severity] = promotedSeverity.name
            }
        }
    }

    override suspend fun findAcknowledgedBefore(cutoff: Instant): List<AlertEvent> =
        newSuspendedTransaction(db = db) {
            AlertEventsTable
                .selectAll()
                .where {
                    (AlertEventsTable.status eq AlertStatus.ACKNOWLEDGED.name) and
                        (AlertEventsTable.acknowledgedAt lessEq OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                }
                .map { it.toAlertEvent() }
        }

    override suspend fun findById(id: String): AlertEvent? = newSuspendedTransaction(db = db) {
        AlertEventsTable
            .selectAll()
            .where { AlertEventsTable.id eq id }
            .map { it.toAlertEvent() }
            .firstOrNull()
    }

    override suspend fun snooze(id: String, until: Instant): AlertEvent? = newSuspendedTransaction(db = db) {
        val updated = AlertEventsTable.update({ AlertEventsTable.id eq id }) {
            it[AlertEventsTable.snoozedUntil] = OffsetDateTime.ofInstant(until, ZoneOffset.UTC)
        }
        if (updated == 0) {
            null
        } else {
            AlertEventsTable
                .selectAll()
                .where { AlertEventsTable.id eq id }
                .map { it.toAlertEvent() }
                .firstOrNull()
        }
    }

    private fun ResultRow.toAlertEvent(): AlertEvent = AlertEvent(
        id = this[AlertEventsTable.id],
        ruleId = this[AlertEventsTable.ruleId],
        ruleName = this[AlertEventsTable.ruleName],
        type = AlertType.valueOf(this[AlertEventsTable.type]),
        severity = Severity.valueOf(this[AlertEventsTable.severity]),
        message = this[AlertEventsTable.message],
        currentValue = this[AlertEventsTable.currentValue].toDouble(),
        threshold = this[AlertEventsTable.threshold].toDouble(),
        bookId = this[AlertEventsTable.bookId],
        triggeredAt = this[AlertEventsTable.triggeredAt].toInstant(),
        status = AlertStatus.valueOf(this[AlertEventsTable.status]),
        acknowledgedAt = this[AlertEventsTable.acknowledgedAt]?.toInstant(),
        resolvedAt = this[AlertEventsTable.resolvedAt]?.toInstant(),
        resolvedReason = this[AlertEventsTable.resolvedReason],
        escalatedAt = this[AlertEventsTable.escalatedAt]?.toInstant(),
        escalatedTo = this[AlertEventsTable.escalatedTo],
        correlationId = this[AlertEventsTable.correlationId],
        contributors = this[AlertEventsTable.contributors],
        suggestedAction = this[AlertEventsTable.suggestedAction],
        snoozedUntil = this[AlertEventsTable.snoozedUntil]?.toInstant(),
    )
}
