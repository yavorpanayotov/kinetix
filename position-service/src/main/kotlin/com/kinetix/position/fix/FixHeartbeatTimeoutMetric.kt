package com.kinetix.position.fix

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Records the observed heartbeat-gap per FIX session as a Micrometer
 * timer: `position.fix.heartbeat.timeout.seconds`. Heartbeat intervals
 * vary by counterparty (10s, 30s, 60s); per-session timers let the
 * platform alert on a slow tier without false alarms from a fast one.
 */
class FixHeartbeatTimeoutMetric(private val registry: MeterRegistry) {

    private val timers: ConcurrentHashMap<String, Timer> = ConcurrentHashMap()

    fun recordGap(sessionId: String, gap: Duration) {
        timers.computeIfAbsent(sessionId) {
            Timer.builder(METRIC_NAME)
                .description("Observed heartbeat-gap for a FIX session")
                .tags(Tags.of("session_id", sessionId))
                .register(registry)
        }.record(gap)
    }

    companion object {
        const val METRIC_NAME = "position.fix.heartbeat.timeout.seconds"
    }
}
