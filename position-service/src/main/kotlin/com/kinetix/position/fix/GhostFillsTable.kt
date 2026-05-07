package com.kinetix.position.fix

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object GhostFillsTable : LongIdTable("ghost_fills", "id") {
    val orderId = varchar("order_id", 255)
    val priorStatus = varchar("prior_status", 30)
    val venue = varchar("venue", 32)
    val fixExecId = varchar("fix_exec_id", 255)
    val fillQty = decimal("fill_qty", 28, 12)
    val fillPrice = decimal("fill_price", 28, 12)
    val cumulativeQty = decimal("cumulative_qty", 28, 12)
    val detectedAt = timestampWithTimeZone("detected_at")
    val rawEvent = text("raw_event")
}
