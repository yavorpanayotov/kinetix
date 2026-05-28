package com.kinetix.gateway.middleware

/** Per-key rate-limit state at the moment a request is dispatched. */
data class RateLimitState(
    val limit: Long,
    val remaining: Long,
    val resetEpochSecond: Long,
)

/**
 * Map a [RateLimitState] to the standard rate-limit response headers
 * (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`)
 * so clients can read their quota and back off proactively rather than
 * hammering until they hit a 429.
 *
 * Negative remaining (rare — pool-drift / concurrent counter racing) is
 * clamped to 0 so the wire value cannot disagree with the actual
 * quota-exhausted truth.
 */
fun rateLimitHeaders(state: RateLimitState): Map<String, String> = mapOf(
    "X-RateLimit-Limit" to state.limit.toString(),
    "X-RateLimit-Remaining" to maxOf(0L, state.remaining).toString(),
    "X-RateLimit-Reset" to state.resetEpochSecond.toString(),
)
