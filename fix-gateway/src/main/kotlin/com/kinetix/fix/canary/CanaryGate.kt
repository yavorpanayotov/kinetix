package com.kinetix.fix.canary

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Gates canary promotion for the `fix-gateway` phase-4 rollout.
 *
 * Promotion is [PromotionDecision.Allowed] only when every one of the three SLIs
 * has been continuously within threshold for at least
 * [SliThresholds.consecutiveHealthyMinutes].  A single breach resets the window
 * (hysteresis), requiring the full consecutive run to be re-earned before the
 * next [PromotionDecision.Allowed] can fire.
 *
 * The three SLIs (from [SliThresholds]):
 *   1. Order rejection rate < [SliThresholds.maxRejectionRatePct]%
 *   2. FIX session uptime  > [SliThresholds.minUptimePct]%
 *   3. Average ack latency < [SliThresholds.maxAvgAckLatencyMs] ms
 *
 * Thread safety: [healthySince] is an [AtomicReference] so concurrent checks
 * (e.g. from a coroutine scheduler and a health-check handler) do not race on
 * window resets.
 *
 * @param thresholds SLI bounds and required window duration.
 * @param sliReader  Supplies the current SLI snapshot; swapped for a test double in
 *                   unit tests.
 * @param clock      Wall-clock used for window calculations; injectable for
 *                   deterministic testing.
 */
class CanaryGate(
    private val thresholds: SliThresholds,
    private val sliReader: SliReader,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(CanaryGate::class.java)

    /**
     * The earliest instant at which all SLIs were last observed to be simultaneously
     * within threshold.  Null means no healthy observation has occurred yet since the
     * gate was constructed or last reset — equivalent to "window restarting now".
     *
     * Set to the construction instant so a fresh gate starts its window immediately
     * when SLIs are healthy, but the window is only [consecutiveHealthyMinutes] long
     * so a fresh gate always blocks until that time elapses.
     */
    private val healthySince: AtomicReference<Instant> = AtomicReference(clock.instant())

    /**
     * Evaluate current SLI snapshot and decide whether to allow promotion.
     *
     * Side effect: when a breach is detected, [healthySince] is reset to the current
     * instant so the consecutive window must be re-earned.
     */
    fun checkPromotion(): PromotionDecision {
        val now = clock.instant()
        val snapshot = readSnapshot()

        val breach = detectBreach(snapshot)
        if (breach != null) {
            resetWindow(now)
            logger.info(
                "CanaryGate blocked: reason={} breachedSli={} snapshot={}",
                breach.reason, breach.breachedSli, snapshot,
            )
            return breach
        }

        val since = healthySince.get()
        val elapsed = Duration.between(since, now)
        val required = Duration.ofMinutes(thresholds.consecutiveHealthyMinutes)

        return if (elapsed >= required) {
            logger.info(
                "CanaryGate allowed: all SLIs healthy for {}m (required {}m)",
                elapsed.toMinutes(), required.toMinutes(),
            )
            PromotionDecision.Allowed
        } else {
            logger.debug(
                "CanaryGate blocked: insufficient_healthy_window elapsed={}m required={}m",
                elapsed.toMinutes(), required.toMinutes(),
            )
            PromotionDecision.Blocked(
                reason = "insufficient_healthy_window",
                breachedSli = null,
            )
        }
    }

    /**
     * Explicitly set the start of the healthy window.  Exposed for testing to pre-seed
     * a window that is already N minutes old without having to sleep real time.
     */
    fun recordHealthySince(instant: Instant) {
        healthySince.set(instant)
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    private data class Snapshot(
        val rejectionRatePct: Double,
        val uptimePct: Double,
        val avgAckLatencyMs: Double,
    )

    private fun readSnapshot() = Snapshot(
        rejectionRatePct = sliReader.rejectionRatePct(),
        uptimePct = sliReader.uptimePct(),
        avgAckLatencyMs = sliReader.avgAckLatencyMs(),
    )

    /**
     * Check each SLI against its threshold. Returns the first [PromotionDecision.Blocked]
     * whose threshold is violated, or null when all SLIs are healthy.
     *
     * Boundaries are exclusive: the threshold is the limit that must NOT be reached.
     * For example, maxRejectionRatePct=0.5 means the current rate must be strictly
     * less than 0.5% — a rate of exactly 0.5% is blocked.
     */
    private fun detectBreach(snapshot: Snapshot): PromotionDecision.Blocked? {
        if (snapshot.rejectionRatePct >= thresholds.maxRejectionRatePct) {
            return PromotionDecision.Blocked(
                reason = "rejection_rate_too_high",
                breachedSli = "rejection_rate_pct",
            )
        }
        if (snapshot.uptimePct <= thresholds.minUptimePct) {
            return PromotionDecision.Blocked(
                reason = "fix_session_uptime_too_low",
                breachedSli = "uptime_pct",
            )
        }
        if (snapshot.avgAckLatencyMs >= thresholds.maxAvgAckLatencyMs) {
            return PromotionDecision.Blocked(
                reason = "ack_latency_too_high",
                breachedSli = "avg_ack_latency_ms",
            )
        }
        return null
    }

    private fun resetWindow(now: Instant) {
        healthySince.set(now)
    }
}
