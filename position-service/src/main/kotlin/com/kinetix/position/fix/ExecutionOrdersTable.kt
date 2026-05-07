package com.kinetix.position.fix

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object ExecutionOrdersTable : Table("execution_orders") {
    val orderId = varchar("order_id", 255)
    val bookId = varchar("book_id", 255)
    val instrumentId = varchar("instrument_id", 255)
    val side = varchar("side", 10)
    val quantity = decimal("quantity", 28, 12)
    val orderType = varchar("order_type", 50)
    val limitPrice = decimal("limit_price", 28, 12).nullable()
    val arrivalPrice = decimal("arrival_price", 28, 12)
    val submittedAt = timestampWithTimeZone("submitted_at")
    val status = varchar("status", 30)
    val riskCheckResult = varchar("risk_check_result", 20).nullable()
    val riskCheckDetails = text("risk_check_details").nullable()
    val fixSessionId = varchar("fix_session_id", 255).nullable()
    val assetClass = varchar("asset_class", 30)
    val currency = varchar("currency", 10)
    val timeInForce = varchar("time_in_force", 10)
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val instrumentType = varchar("instrument_type", 50).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(orderId)
}
