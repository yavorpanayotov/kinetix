package com.kinetix.fix.canary

/**
 * Abstraction over the live SLI snapshot consumed by [CanaryGate].
 *
 * Separating the read concern from the gate logic keeps the gate unit-testable
 * without a live [io.micrometer.core.instrument.MeterRegistry] — tests supply
 * a [StaticSliReader] (or any in-test implementation) and the gate never knows
 * the difference.
 *
 * The production implementation is [MicrometerSliReader], which derives values
 * from counters and timers already registered by [FixGatewayServiceImpl] and
 * [PendingNewCorrelator].
 */
interface SliReader {
    /** Current order rejection rate as a percentage (0–100). */
    fun rejectionRatePct(): Double

    /** Current FIX session uptime as a percentage (0–100). */
    fun uptimePct(): Double

    /** Current average order-acknowledgement latency in milliseconds. */
    fun avgAckLatencyMs(): Double
}
