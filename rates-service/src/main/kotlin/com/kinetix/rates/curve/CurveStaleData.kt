package com.kinetix.rates.curve

import java.time.Instant

/**
 * Flag a yield-curve as stale when [lastUpdate] is more than
 * [thresholdHours] (default 4) before [now]. Yield-curve feeds
 * normally refresh every 5-15 minutes during market hours; a curve
 * stale longer than 4 hours is almost certainly stuck and the desk
 * needs to know before pricing off it.
 *
 * `null` lastUpdate is stale (never received). Future-dated lastUpdate
 * (clock skew) is fresh — better to honour the upstream value than
 * chase a phantom staleness alert.
 */
fun isCurveStale(
    lastUpdate: Instant?,
    now: Instant,
    thresholdHours: Long = 4L,
): Boolean {
    if (lastUpdate == null) return true
    val ageSeconds = now.epochSecond - lastUpdate.epochSecond
    if (ageSeconds < 0) return false
    return ageSeconds >= thresholdHours * 3600L
}
