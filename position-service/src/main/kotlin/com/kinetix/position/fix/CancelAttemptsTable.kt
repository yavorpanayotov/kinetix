package com.kinetix.position.fix

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object CancelAttemptsTable : LongIdTable("cancel_attempts", "id") {
    val orderId = varchar("order_id", 255)
    val venue = varchar("venue", 32)
    val status = varchar("status", 32)
    val detail = text("detail")
    val attemptedAt = timestampWithTimeZone("attempted_at")
}
