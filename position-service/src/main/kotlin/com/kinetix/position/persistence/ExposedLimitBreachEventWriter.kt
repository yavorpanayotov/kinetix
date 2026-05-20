package com.kinetix.position.persistence

import com.kinetix.position.model.LimitBreachEvent
import com.kinetix.position.model.LimitBreachSeverity
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExposedLimitBreachEventWriter(private val db: Database? = null) : LimitBreachEventWriter {

    override suspend fun recordBreach(
        entityId: String,
        bookId: String,
        limitType: String,
        severity: LimitBreachSeverity,
        currentValue: BigDecimal,
        limitValue: BigDecimal,
        breachedAt: Instant,
    ): LimitBreachEvent = newSuspendedTransaction(db = db) {
        val existing = LimitBreachEventsTable
            .selectAll()
            .where {
                (LimitBreachEventsTable.entityId eq entityId) and
                    (LimitBreachEventsTable.bookId eq bookId) and
                    (LimitBreachEventsTable.limitType eq limitType) and
                    (LimitBreachEventsTable.resolvedAt.isNull())
            }
            .firstOrNull()
            ?.toModel()
        if (existing != null) {
            existing
        } else {
            val newId = UUID.randomUUID()
            val breachedAtOffset = breachedAt.atOffset(ZoneOffset.UTC)
            LimitBreachEventsTable.insert {
                it[id] = newId
                it[LimitBreachEventsTable.entityId] = entityId
                it[LimitBreachEventsTable.bookId] = bookId
                it[LimitBreachEventsTable.limitType] = limitType
                it[LimitBreachEventsTable.severity] = severity.name
                it[LimitBreachEventsTable.currentValue] = currentValue
                it[LimitBreachEventsTable.limitValue] = limitValue
                it[LimitBreachEventsTable.breachedAt] = breachedAtOffset
                it[resolvedAt] = null
            }
            LimitBreachEvent(
                id = newId,
                entityId = entityId,
                bookId = bookId,
                limitType = limitType,
                severity = severity,
                currentValue = currentValue,
                limitValue = limitValue,
                breachedAt = breachedAt,
                resolvedAt = null,
            )
        }
    }

    override suspend fun recordResolution(
        entityId: String,
        bookId: String,
        limitType: String,
        resolvedAt: Instant,
    ): Int = newSuspendedTransaction(db = db) {
        val resolvedAtOffset = resolvedAt.atOffset(ZoneOffset.UTC)
        LimitBreachEventsTable.update({
            (LimitBreachEventsTable.entityId eq entityId) and
                (LimitBreachEventsTable.bookId eq bookId) and
                (LimitBreachEventsTable.limitType eq limitType) and
                (LimitBreachEventsTable.resolvedAt.isNull())
        }) {
            it[LimitBreachEventsTable.resolvedAt] = resolvedAtOffset
        }
    }

    override suspend fun findByBook(bookId: String): List<LimitBreachEvent> = newSuspendedTransaction(db = db) {
        LimitBreachEventsTable
            .selectAll()
            .where { LimitBreachEventsTable.bookId eq bookId }
            .orderBy(LimitBreachEventsTable.breachedAt, SortOrder.DESC)
            .map { it.toModel() }
    }

    private fun ResultRow.toModel(): LimitBreachEvent = LimitBreachEvent(
        id = this[LimitBreachEventsTable.id],
        entityId = this[LimitBreachEventsTable.entityId],
        bookId = this[LimitBreachEventsTable.bookId],
        limitType = this[LimitBreachEventsTable.limitType],
        severity = LimitBreachSeverity.valueOf(this[LimitBreachEventsTable.severity]),
        currentValue = this[LimitBreachEventsTable.currentValue],
        limitValue = this[LimitBreachEventsTable.limitValue],
        breachedAt = this[LimitBreachEventsTable.breachedAt].toInstant(),
        resolvedAt = this[LimitBreachEventsTable.resolvedAt]?.toInstant(),
    )
}
