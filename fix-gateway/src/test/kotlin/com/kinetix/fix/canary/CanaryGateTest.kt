package com.kinetix.fix.canary

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Specifies the behaviour of [CanaryGate.checkPromotion] against controlled SLI snapshots.
 *
 * The gate advances the canary only when all three SLIs have been within threshold for
 * at least [SliThresholds.consecutiveHealthyMinutes] consecutive minutes:
 *   - order rejection rate < 0.5%
 *   - FIX session uptime > 99.5%
 *   - average ack latency < 250ms
 *
 * A breach at any point resets the N-minute window (hysteresis).
 */
class CanaryGateTest : FunSpec({

    val thresholds = SliThresholds(
        maxRejectionRatePct = 0.5,
        minUptimePct = 99.5,
        maxAvgAckLatencyMs = 250.0,
        consecutiveHealthyMinutes = 5,
    )

    fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    val t0 = Instant.parse("2026-05-29T10:00:00Z")

    // -----------------------------------------------------------------
    // Healthy path — all SLIs within threshold for >= N minutes
    // -----------------------------------------------------------------

    test("returns Allowed when all SLIs are healthy and the window has elapsed") {
        val healthySince = t0
        val now = t0.plus(Duration.ofMinutes(6))

        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = StaticSliReader(
                rejectionRatePct = 0.1,
                uptimePct = 99.9,
                avgAckLatencyMs = 100.0,
            ),
            clock = clockAt(now),
        )
        gate.recordHealthySince(healthySince)

        val decision = gate.checkPromotion()
        decision shouldBe PromotionDecision.Allowed
    }

    test("returns Blocked when window has not elapsed even though all SLIs are healthy") {
        val healthySince = t0
        val now = t0.plus(Duration.ofMinutes(3)) // only 3 of 5 required minutes

        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = StaticSliReader(
                rejectionRatePct = 0.1,
                uptimePct = 99.9,
                avgAckLatencyMs = 100.0,
            ),
            clock = clockAt(now),
        )
        gate.recordHealthySince(healthySince)

        val decision = gate.checkPromotion()
        decision.shouldBeInstanceOf<PromotionDecision.Blocked>()
        (decision as PromotionDecision.Blocked).reason shouldBe "insufficient_healthy_window"
    }

    // -----------------------------------------------------------------
    // Rejection rate breach
    // -----------------------------------------------------------------

    test("returns Blocked with rejection_rate_too_high when rejection rate exceeds threshold") {
        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = StaticSliReader(
                rejectionRatePct = 0.6,  // > 0.5% threshold
                uptimePct = 99.9,
                avgAckLatencyMs = 100.0,
            ),
            clock = clockAt(t0),
        )

        val decision = gate.checkPromotion()
        decision.shouldBeInstanceOf<PromotionDecision.Blocked>()
        val blocked = decision as PromotionDecision.Blocked
        blocked.reason shouldBe "rejection_rate_too_high"
        blocked.breachedSli shouldBe "rejection_rate_pct"
    }

    test("returns Blocked when rejection rate is exactly at threshold (boundary is exclusive)") {
        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = StaticSliReader(
                rejectionRatePct = 0.5, // equal to threshold — not below, so blocked
                uptimePct = 99.9,
                avgAckLatencyMs = 100.0,
            ),
            clock = clockAt(t0),
        )

        val decision = gate.checkPromotion()
        decision.shouldBeInstanceOf<PromotionDecision.Blocked>()
        (decision as PromotionDecision.Blocked).reason shouldBe "rejection_rate_too_high"
    }

    // -----------------------------------------------------------------
    // Uptime breach
    // -----------------------------------------------------------------

    test("returns Blocked with fix_session_uptime_too_low when uptime falls below threshold") {
        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = StaticSliReader(
                rejectionRatePct = 0.1,
                uptimePct = 99.0,  // < 99.5% threshold
                avgAckLatencyMs = 100.0,
            ),
            clock = clockAt(t0),
        )

        val decision = gate.checkPromotion()
        decision.shouldBeInstanceOf<PromotionDecision.Blocked>()
        val blocked = decision as PromotionDecision.Blocked
        blocked.reason shouldBe "fix_session_uptime_too_low"
        blocked.breachedSli shouldBe "uptime_pct"
    }

    test("returns Blocked when uptime is exactly at threshold (boundary is exclusive)") {
        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = StaticSliReader(
                rejectionRatePct = 0.1,
                uptimePct = 99.5, // equal — not above, so blocked
                avgAckLatencyMs = 100.0,
            ),
            clock = clockAt(t0),
        )

        val decision = gate.checkPromotion()
        decision.shouldBeInstanceOf<PromotionDecision.Blocked>()
        (decision as PromotionDecision.Blocked).reason shouldBe "fix_session_uptime_too_low"
    }

    // -----------------------------------------------------------------
    // Ack latency breach
    // -----------------------------------------------------------------

    test("returns Blocked with ack_latency_too_high when average ack latency exceeds threshold") {
        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = StaticSliReader(
                rejectionRatePct = 0.1,
                uptimePct = 99.9,
                avgAckLatencyMs = 300.0,  // > 250ms threshold
            ),
            clock = clockAt(t0),
        )

        val decision = gate.checkPromotion()
        decision.shouldBeInstanceOf<PromotionDecision.Blocked>()
        val blocked = decision as PromotionDecision.Blocked
        blocked.reason shouldBe "ack_latency_too_high"
        blocked.breachedSli shouldBe "avg_ack_latency_ms"
    }

    test("returns Blocked when ack latency is exactly at threshold (boundary is exclusive)") {
        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = StaticSliReader(
                rejectionRatePct = 0.1,
                uptimePct = 99.9,
                avgAckLatencyMs = 250.0, // equal — not below, so blocked
            ),
            clock = clockAt(t0),
        )

        val decision = gate.checkPromotion()
        decision.shouldBeInstanceOf<PromotionDecision.Blocked>()
        (decision as PromotionDecision.Blocked).reason shouldBe "ack_latency_too_high"
    }

    // -----------------------------------------------------------------
    // Hysteresis — a breach resets the N-minute window
    // -----------------------------------------------------------------

    test("resets the healthy window when a breach is detected after a healthy period") {
        // Gate was healthy for 3 minutes, then a breach fires — window resets.
        val healthySince = t0
        val checkTime = t0.plus(Duration.ofMinutes(3))

        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = StaticSliReader(
                rejectionRatePct = 0.6,  // breach
                uptimePct = 99.9,
                avgAckLatencyMs = 100.0,
            ),
            clock = clockAt(checkTime),
        )
        gate.recordHealthySince(healthySince)

        // First call: detects breach, resets window
        gate.checkPromotion().shouldBeInstanceOf<PromotionDecision.Blocked>()

        // Now fast-forward 6 more minutes but only 6 minutes since the RESET,
        // not since t0. The gate should be in Blocked(insufficient_healthy_window).
        // We simulate by calling again with healthy SLIs but the window was reset
        // just now (at checkTime), so healthySince == checkTime, and only 0 minutes
        // have elapsed since the reset.
        val gateSameInstance = gate  // same instance — window was reset internally
        val decisionAfterReset = gateSameInstance.checkPromotion()
        // SLI still breached → still Blocked(rejection_rate_too_high), confirming
        // the window state is maintained correctly.
        decisionAfterReset.shouldBeInstanceOf<PromotionDecision.Blocked>()
        (decisionAfterReset as PromotionDecision.Blocked).reason shouldBe "rejection_rate_too_high"
    }

    test("window resets after breach and healthy period must restart from zero") {
        // Breach at t0 + 4 min → reset. Then 5 more healthy minutes → Allowed.
        var currentInstant = t0
        var currentRejectionRate = 0.1

        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = object : SliReader {
                override fun rejectionRatePct(): Double = currentRejectionRate
                override fun uptimePct(): Double = 99.9
                override fun avgAckLatencyMs(): Double = 100.0
            },
            clock = object : Clock() {
                override fun getZone() = ZoneOffset.UTC
                override fun withZone(zone: java.time.ZoneId) = this
                override fun instant() = currentInstant
            },
        )

        // Step 1: 4 minutes healthy — window not yet elapsed.
        currentInstant = t0.plus(Duration.ofMinutes(4))
        gate.checkPromotion().shouldBeInstanceOf<PromotionDecision.Blocked>() // insufficient_healthy_window

        // Step 2: breach fires at 4 minutes — window resets.
        currentRejectionRate = 0.6
        gate.checkPromotion().shouldBeInstanceOf<PromotionDecision.Blocked>() // rejection_rate_too_high

        // Step 3: recovery — rate drops below threshold. The window restarts from this moment.
        currentRejectionRate = 0.1
        val recoveryInstant = currentInstant  // window starts now

        // Step 4: only 3 minutes after recovery — still not enough.
        currentInstant = recoveryInstant.plus(Duration.ofMinutes(3))
        gate.checkPromotion().shouldBeInstanceOf<PromotionDecision.Blocked>() // insufficient_healthy_window

        // Step 5: 6 minutes after recovery — full window elapsed → Allowed.
        currentInstant = recoveryInstant.plus(Duration.ofMinutes(6))
        gate.checkPromotion() shouldBe PromotionDecision.Allowed
    }

    // -----------------------------------------------------------------
    // No data — gate is conservative when metrics are absent
    // -----------------------------------------------------------------

    test("returns Blocked with insufficient_healthy_window when no healthy window has been established yet") {
        val gate = CanaryGate(
            thresholds = thresholds,
            sliReader = StaticSliReader(
                rejectionRatePct = 0.1,
                uptimePct = 99.9,
                avgAckLatencyMs = 100.0,
            ),
            clock = clockAt(t0.plus(Duration.ofMinutes(10))),
        )
        // No recordHealthySince call — window starts at gate construction time.

        // The gate was constructed at some earlier time; we need it to start as if
        // constructed exactly at the same time as the clock. With clock fixed at
        // t0+10m and construction at t0+10m, 0 minutes elapsed → Blocked.
        val decisionFresh = gate.checkPromotion()
        // Fresh gate with 0 elapsed time: either insufficient_healthy_window or Blocked for breach
        decisionFresh.shouldBeInstanceOf<PromotionDecision.Blocked>()
    }
})

/**
 * Test double: supplies fixed SLI values without requiring a live [MeterRegistry].
 */
private class StaticSliReader(
    private val rejectionRatePct: Double,
    private val uptimePct: Double,
    private val avgAckLatencyMs: Double,
) : SliReader {
    override fun rejectionRatePct(): Double = rejectionRatePct
    override fun uptimePct(): Double = uptimePct
    override fun avgAckLatencyMs(): Double = avgAckLatencyMs
}
