package com.kinetix.audit.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object AuditEventsTable : Table("audit_events") {
    val id = long("id").autoIncrement()
    // Trade fields — nullable to accommodate governance events
    val tradeId = varchar("trade_id", 255).nullable()
    val bookId = varchar("book_id", 255).nullable()
    val instrumentId = varchar("instrument_id", 255).nullable()
    val assetClass = varchar("asset_class", 50).nullable()
    val side = varchar("side", 10).nullable()
    val quantity = decimal("quantity", 28, 12).nullable()
    val priceAmount = decimal("price_amount", 28, 12).nullable()
    val priceCurrency = varchar("price_currency", 3).nullable()
    val tradedAt = timestampWithTimeZone("traded_at").nullable()
    val receivedAt = timestampWithTimeZone("received_at")
    val previousHash = varchar("previous_hash", 64).nullable()
    val recordHash = varchar("record_hash", 64).default("")
    val userId = varchar("user_id", 255).nullable()
    val userRole = varchar("user_role", 100).nullable()
    val traderId = varchar("trader_id", 64).nullable()
    val eventType = varchar("event_type", 100).default("TRADE_BOOKED")
    // Governance fields — null for trade events
    val modelName = varchar("model_name", 255).nullable()
    val scenarioId = varchar("scenario_id", 255).nullable()
    val limitId = varchar("limit_id", 255).nullable()
    val submissionId = varchar("submission_id", 255).nullable()
    val details = text("details").nullable()
    val sequenceNumber = long("sequence_number").nullable()
    // Operational metadata — not part of the hash chain (see AuditHasher).
    val correlationId = varchar("correlation_id", 255).nullable()

    override val primaryKey = PrimaryKey(id)
}
