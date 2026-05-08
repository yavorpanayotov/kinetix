package com.kinetix.fix.session

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

/**
 * Exposed DSL mapping for `fix_message_log` (V2 migration). Partitioned by `sent_at`
 * month on Postgres; the Exposed ORM treats it as a regular table — partitioning is
 * transparent at the JDBC level.
 */
object FixMessageLogTable : Table("fix_message_log") {
    val id = long("id").autoIncrement()
    val venue = varchar("venue", 32)
    val direction = varchar("direction", 8)
    val msgType = varchar("msg_type", 8)
    val rawMessage = text("raw_message")
    val clOrdId = varchar("clord_id", 64).nullable()
    val venueOrderId = varchar("venue_order_id", 64).nullable()
    val orderStatus = varchar("order_status", 16)
    val sentAt = timestampWithTimeZone("sent_at")

    // Composite primary key on (id, sent_at) matches the partitioned table DDL.
    override val primaryKey = PrimaryKey(id, sentAt)
}
