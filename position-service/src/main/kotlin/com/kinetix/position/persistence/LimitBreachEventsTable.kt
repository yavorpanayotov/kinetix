package com.kinetix.position.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

// Maps the `limit_breach_events` table created by Flyway migration
// V32__create_limit_breach_events.sql. Each row records a single limit
// breach — `resolved_at` stays NULL while the breach is open and is
// filled in when it clears. Powers the AI v2 morning-brief breach history.
object LimitBreachEventsTable : Table("limit_breach_events") {
    val id = uuid("id")
    val entityId = text("entity_id")
    val bookId = text("book_id")
    val limitType = text("limit_type")
    val severity = text("severity")
    val currentValue = decimal("current_value", 28, 12)
    val limitValue = decimal("limit_value", 28, 12)
    val breachedAt = timestampWithTimeZone("breached_at")
    val resolvedAt = timestampWithTimeZone("resolved_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
