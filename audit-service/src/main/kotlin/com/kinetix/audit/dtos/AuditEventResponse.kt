package com.kinetix.audit.dtos

import com.kinetix.audit.model.AuditEvent
import kotlinx.serialization.Serializable

@Serializable
data class AuditEventResponse(
    val id: Long,
    // Trade fields — nullable for governance events
    val tradeId: String? = null,
    val bookId: String? = null,
    val instrumentId: String? = null,
    val assetClass: String? = null,
    val side: String? = null,
    val quantity: String? = null,
    val priceAmount: String? = null,
    val priceCurrency: String? = null,
    val tradedAt: String? = null,
    // Common fields
    val receivedAt: String,
    val previousHash: String? = null,
    val recordHash: String = "",
    val userId: String? = null,
    val userRole: String? = null,
    val eventType: String = "TRADE_BOOKED",
    // Governance fields — null for trade events
    val modelName: String? = null,
    val scenarioId: String? = null,
    val limitId: String? = null,
    val submissionId: String? = null,
    val details: String? = null,
    val sequenceNumber: Long? = null,
    /** End-to-end request correlation id (audit-v2, migration V13); null when not captured. */
    val correlationId: String? = null,
)

fun AuditEvent.toResponse(): AuditEventResponse = AuditEventResponse(
    id = id,
    tradeId = tradeId,
    bookId = bookId,
    instrumentId = instrumentId,
    assetClass = assetClass,
    side = side,
    quantity = quantity,
    priceAmount = priceAmount,
    priceCurrency = priceCurrency,
    tradedAt = tradedAt,
    receivedAt = receivedAt.toString(),
    previousHash = previousHash,
    recordHash = recordHash,
    userId = userId,
    userRole = userRole,
    eventType = eventType,
    modelName = modelName,
    scenarioId = scenarioId,
    limitId = limitId,
    submissionId = submissionId,
    details = details,
    sequenceNumber = sequenceNumber,
    correlationId = correlationId,
)
