package com.kinetix.notification.engine

import java.time.Instant

/**
 * Per-(rule, trader) snooze state for alert delivery.
 *
 * Traders silence known-noisy rules during maintenance windows — the
 * 22:00 ET curve rebuild fires a staleness alert by design, and the
 * trader does not need to be paged about it every minute. Snoozes are
 * scoped per-rule-per-trader so one trader's snooze does not silence
 * another trader's delivery of the same rule.
 *
 * State is in-memory and per-process. Cross-replica coordination is not
 * required because upstream consumer-group partitioning already pins a
 * given (rule, trader) key to a single replica.
 */
class AlertSnoozeWindow {

    /** Key -> Instant when the snooze expires. */
    private val expiresAt: MutableMap<Pair<String, String>, Instant> = mutableMapOf()

    /** Mark a (rule, trader) snoozed for [durationSeconds] starting at [snoozedAt]. */
    fun snooze(ruleId: String, traderId: String, snoozedAt: Instant, durationSeconds: Long) {
        expiresAt[ruleId to traderId] = snoozedAt.plusSeconds(durationSeconds)
    }

    /** Returns true if the rule is currently snoozed for the trader at [now]. */
    fun isSnoozed(ruleId: String, traderId: String, now: Instant): Boolean {
        val expires = expiresAt[ruleId to traderId] ?: return false
        return !now.isAfter(expires)
    }

    /** Manually clear a snooze. */
    fun clear(ruleId: String, traderId: String) {
        expiresAt.remove(ruleId to traderId)
    }
}
