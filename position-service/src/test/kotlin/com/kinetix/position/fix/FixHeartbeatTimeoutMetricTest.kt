package com.kinetix.position.fix

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration

/**
 * The position-service consumes FIX session traffic. Heartbeat
 * intervals from upstream counterparties vary (10s, 30s, 60s); a
 * missed heartbeat past the configured grace window indicates the
 * session is hung. The metric `position.fix.heartbeat.timeout.seconds`
 * records the observed gap, tagged with the session id, so the
 * platform can alert on a slow tier without false alarms from a fast
 * tier.
 */
class FixHeartbeatTimeoutMetricTest : FunSpec({

    test("records a heartbeat-gap sample tagged with session id") {
        val registry = SimpleMeterRegistry()
        val metric = FixHeartbeatTimeoutMetric(registry)
        metric.recordGap(sessionId = "GS-EQ-1", gap = Duration.ofSeconds(35))
        val timer = registry.find(FixHeartbeatTimeoutMetric.METRIC_NAME)
            .tag("session_id", "GS-EQ-1").timer()
        timer!!.count() shouldBe 1L
        timer.totalTime(java.util.concurrent.TimeUnit.SECONDS) shouldBeGreaterThan 34.0
    }

    test("different sessions register independently") {
        val registry = SimpleMeterRegistry()
        val metric = FixHeartbeatTimeoutMetric(registry)
        metric.recordGap("GS-EQ-1", Duration.ofSeconds(35))
        metric.recordGap("MS-FX-2", Duration.ofSeconds(12))
        registry.find(FixHeartbeatTimeoutMetric.METRIC_NAME).timers().size shouldBe 2
    }

    test("repeated samples on the same session accumulate") {
        val registry = SimpleMeterRegistry()
        val metric = FixHeartbeatTimeoutMetric(registry)
        repeat(3) { metric.recordGap("GS-EQ-1", Duration.ofSeconds(10)) }
        registry.find(FixHeartbeatTimeoutMetric.METRIC_NAME)
            .tag("session_id", "GS-EQ-1").timer()!!.count() shouldBe 3L
    }
})
