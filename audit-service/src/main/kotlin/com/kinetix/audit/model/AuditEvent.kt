package com.kinetix.audit.model

import java.time.Instant

data class AuditEvent(
    val id: Long = 0,
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
    val receivedAt: Instant,
    val previousHash: String? = null,
    val recordHash: String = "",
    val userId: String? = null,
    val userRole: String? = null,
    val traderId: String? = null,
    val eventType: String = "TRADE_BOOKED",
    // Governance fields — null for trade events
    val modelName: String? = null,
    val scenarioId: String? = null,
    val limitId: String? = null,
    val submissionId: String? = null,
    val details: String? = null,
    val sequenceNumber: Long? = null,
    // Operational metadata — a cross-reference pointer to Loki logs / Tempo traces.
    // Intentionally excluded from AuditHasher's hash-chain input (see AuditHasher).
    val correlationId: String? = null,
    // Kafka inbox-dedup coordinates — the (topic, partition, offset) of the
    // source record. Populated by the Kafka consumers; NULL for non-Kafka
    // inserts (DLQ replay, dev seeding). Operational metadata: intentionally
    // excluded from AuditHasher's hash-chain input, exactly like correlationId.
    val sourceTopic: String? = null,
    val sourcePartition: Int? = null,
    val sourceOffset: Long? = null,
)
