package com.kinetix.price.quote

/**
 * Validate a bid/ask quote: both legs must be finite and
 * non-negative, and `bid <= ask` (zero spread = locked market is OK,
 * but an inverted pair is bad data).
 *
 * Mangled quotes (inverted, negative) corrupt the spread used by
 * downstream pricers and P&L attribution. Catching them at ingestion
 * keeps the nonsense out of the audit chain.
 */
fun validateBidAsk(bid: Double, ask: Double) {
    require(bid.isFinite()) { "bid is non-finite ($bid)" }
    require(ask.isFinite()) { "ask is non-finite ($ask)" }
    require(bid >= 0.0) { "bid must be non-negative (got $bid)" }
    require(ask >= 0.0) { "ask must be non-negative (got $ask)" }
    require(bid <= ask) { "inverted quote: bid $bid > ask $ask" }
}
