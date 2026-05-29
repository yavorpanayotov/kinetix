package com.kinetix.fix.canary

/**
 * Configuration thresholds for the three SLIs that gate canary promotion.
 *
 * @param maxRejectionRatePct       Maximum order-rejection rate expressed as a percentage
 *                                  of total outbound orders (exclusive upper bound).
 *                                  Default: 0.5 (i.e. < 0.5%).
 * @param minUptimePct              Minimum FIX-session uptime expressed as a percentage
 *                                  (exclusive lower bound).
 *                                  Default: 99.5 (i.e. > 99.5%).
 * @param maxAvgAckLatencyMs        Maximum average order-acknowledgement latency in
 *                                  milliseconds (exclusive upper bound).
 *                                  Default: 250.0 ms.
 * @param consecutiveHealthyMinutes Number of consecutive minutes all SLIs must remain
 *                                  within threshold before promotion is [PromotionDecision.Allowed].
 *                                  Default: 5 minutes.
 */
data class SliThresholds(
    val maxRejectionRatePct: Double = 0.5,
    val minUptimePct: Double = 99.5,
    val maxAvgAckLatencyMs: Double = 250.0,
    val consecutiveHealthyMinutes: Long = 5,
)
