package com.kinetix.risk.orchestrator.staleness

import java.time.Instant

/**
 * Returns `true` when the cached Greeks should be recomputed: either no
 * prior compute (`computedAt == null`) or the age (`now - computedAt`)
 * has met or exceeded the threshold. The 5-minute default lets the
 * orchestrator avoid hammering the risk engine on every UI poll while
 * keeping the dashboards within a UX-acceptable freshness window.
 *
 * Clock skew (future-dated `computedAt`) is treated as fresh — better
 * to serve a one-second-future cache than to spam a recalc loop.
 */
fun isGreeksStale(
    computedAt: Instant?,
    now: Instant,
    staleAfterSeconds: Long,
): Boolean {
    if (computedAt == null) return true
    val ageSeconds = now.epochSecond - computedAt.epochSecond
    if (ageSeconds < 0) return false
    return ageSeconds >= staleAfterSeconds
}
