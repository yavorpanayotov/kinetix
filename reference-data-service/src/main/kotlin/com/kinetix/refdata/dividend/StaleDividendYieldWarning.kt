package com.kinetix.refdata.dividend

import java.time.Instant

/**
 * Flag a dividend yield as stale when it was last updated more than
 * [thresholdDays] (default 30) before `now`. Stale yields propagate
 * into the risk engine as wrong-sized dividend Greeks; the warning
 * either surfaces a UI badge or causes the calculator to fall back to
 * the curve-implied estimate.
 *
 * `null` updatedAt is treated as stale (the yield has never been
 * received). Future-dated `updatedAt` (clock skew) is treated as
 * fresh — better to honour the upstream value than chase a phantom
 * staleness alert.
 */
fun isDividendYieldStale(
    updatedAt: Instant?,
    now: Instant,
    thresholdDays: Long = 30L,
): Boolean {
    if (updatedAt == null) return true
    val ageSeconds = now.epochSecond - updatedAt.epochSecond
    if (ageSeconds < 0) return false
    val thresholdSeconds = thresholdDays * 24L * 3600L
    return ageSeconds >= thresholdSeconds
}
