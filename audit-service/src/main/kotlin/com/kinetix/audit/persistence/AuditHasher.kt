package com.kinetix.audit.persistence

import com.kinetix.audit.model.AuditEvent
import java.security.MessageDigest

object AuditHasher {

    // Note: AuditEvent.correlationId is intentionally excluded from the hash input.
    // It is operational metadata (a cross-reference to Loki/Tempo), not a regulatory
    // fact — the hash chain only protects who/what/when/trade detail.
    fun computeHash(event: AuditEvent, previousHash: String?): String {
        val data = buildString {
            append(event.receivedAt)
            append(event.tradeId ?: "")
            append(event.bookId ?: "")
            append(event.instrumentId ?: "")
            append(event.assetClass ?: "")
            append(event.side ?: "")
            append(event.quantity ?: "")
            append(event.priceAmount ?: "")
            append(event.priceCurrency ?: "")
            append(event.tradedAt ?: "")
            append(event.userId ?: "NULL")
            append(event.userRole ?: "NULL")
            append(event.traderId ?: "NULL")
            append(event.eventType)
            append(event.modelName ?: "")
            append(event.scenarioId ?: "")
            append(event.limitId ?: "")
            append(event.submissionId ?: "")
            append(event.details ?: "")
            append(previousHash ?: "NULL")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyChain(events: List<AuditEvent>): ChainVerificationResult {
        if (events.isEmpty()) {
            return ChainVerificationResult(valid = true, eventCount = 0)
        }

        var previousHash: String? = null
        for (event in events) {
            if (event.previousHash != previousHash) {
                return ChainVerificationResult(valid = false, eventCount = events.size)
            }
            val expectedHash = computeHash(event, previousHash)
            if (event.recordHash != expectedHash) {
                return ChainVerificationResult(valid = false, eventCount = events.size)
            }
            previousHash = event.recordHash
        }
        return ChainVerificationResult(valid = true, eventCount = events.size)
    }

    fun verifyChainIncremental(
        events: List<AuditEvent>,
        startingPreviousHash: String?,
    ): IncrementalVerificationResult {
        if (events.isEmpty()) {
            return IncrementalVerificationResult(valid = true, lastHash = startingPreviousHash, eventsVerified = 0)
        }

        var previousHash = startingPreviousHash
        for (event in events) {
            if (event.previousHash != previousHash) {
                return IncrementalVerificationResult(valid = false, lastHash = previousHash, eventsVerified = 0)
            }
            val expectedHash = computeHash(event, previousHash)
            if (event.recordHash != expectedHash) {
                return IncrementalVerificationResult(valid = false, lastHash = previousHash, eventsVerified = 0)
            }
            previousHash = event.recordHash
        }
        return IncrementalVerificationResult(valid = true, lastHash = previousHash, eventsVerified = events.size)
    }
}
