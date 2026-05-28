package com.kinetix.price.ingestion

/**
 * Domain-specific exception thrown when a price tick fails the
 * ingestion-time sanity gate. Carries the offending instrument so
 * the audit-service can correlate the rejection with the upstream
 * feed.
 */
class InvalidPriceException(
    val instrumentId: String,
    val rejectedValue: Double,
    reason: String,
) : IllegalArgumentException("InvalidPrice for $instrumentId ($rejectedValue): $reason")

/**
 * Validate a price tick before persistence. Rejects zero (almost
 * always bad data), negative (impossible for a regular cash market),
 * NaN, and Infinity. Caller catches [InvalidPriceException] to emit
 * a structured audit event.
 */
fun validateIngestedPrice(instrumentId: String, price: Double) {
    if (!price.isFinite()) {
        throw InvalidPriceException(instrumentId, price, "non-finite value")
    }
    if (price == 0.0) {
        throw InvalidPriceException(instrumentId, price, "refusing zero price tick")
    }
    if (price < 0.0) {
        throw InvalidPriceException(instrumentId, price, "negative price")
    }
}
