package com.kinetix.notification.engine

import java.time.Instant

/**
 * Per-(rule, entity) sliding-window deduplication for alert delivery.
 *
 * Telemetry arrives in bursts: a single price spike can fire the same
 * VaR-breach rule on the same trade three times in eight seconds. Without
 * suppression the trader's blotter floods and the on-call pager pings
 * repeatedly for what is one underlying event. This window remembers the
 * most-recent delivery for each `(ruleId, entityId)` key and silently
 * drops any subsequent firing within [windowSeconds] of that anchor.
 *
 * The state is intentionally in-memory: alert delivery is best-effort by
 * design, and a fresh process restart is permitted to re-page once. The
 * window is per-process, with no cross-replica coordination — that's
 * sufficient because the upstream consumer-group partitioning already
 * pins a (rule, entity) key to a single replica.
 */
class DuplicateAlertSuppressionWindow(
    private val windowSeconds: Long,
) {
    private val lastDelivered: MutableMap<Pair<String, String>, Instant> = mutableMapOf()

    /**
     * Returns `true` if the alert should be delivered, `false` if it
     * is a duplicate within the suppression window. Caller is expected
     * to invoke this exactly once per pending alert; a `true` result
     * implicitly anchors the window at [firedAt] for that key.
     */
    fun shouldDeliver(ruleId: String, entityId: String, firedAt: Instant): Boolean {
        val key = ruleId to entityId
        val anchor = lastDelivered[key]
        if (anchor != null) {
            val ageSeconds = firedAt.epochSecond - anchor.epochSecond
            if (ageSeconds <= windowSeconds) return false
        }
        lastDelivered[key] = firedAt
        return true
    }
}
