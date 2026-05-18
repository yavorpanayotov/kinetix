package com.kinetix.notification.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object AlertEventsTable : Table("alert_events") {
    val id = varchar("id", 255)
    val ruleId = varchar("rule_id", 255)
    val ruleName = varchar("rule_name", 255)
    val type = varchar("type", 50)
    val severity = varchar("severity", 50)
    val message = text("message")
    val currentValue = decimal("current_value", 20, 6)
    val threshold = decimal("threshold", 20, 6)
    val bookId = varchar("book_id", 255)
    val triggeredAt = timestampWithTimeZone("triggered_at")
    val status = varchar("status", 20).default("TRIGGERED")
    val acknowledgedAt = timestampWithTimeZone("acknowledged_at").nullable()
    val resolvedAt = timestampWithTimeZone("resolved_at").nullable()
    val resolvedReason = text("resolved_reason").nullable()
    val escalatedAt = timestampWithTimeZone("escalated_at").nullable()
    val escalatedTo = varchar("escalated_to", 255).nullable()
    val contributors = text("contributors").nullable()
    val correlationId = varchar("correlation_id", 255).nullable()
    val suggestedAction = text("suggested_action").nullable()
    val snoozedUntil = timestampWithTimeZone("snoozed_until").nullable()

    override val primaryKey = PrimaryKey(id)
}
