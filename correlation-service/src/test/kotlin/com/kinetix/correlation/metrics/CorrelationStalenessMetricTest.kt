package com.kinetix.correlation.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Pairwise correlation feeds refresh on different schedules — major
 * equity pairs daily, exotic cross-rates weekly. The metric
 * `correlation.pair.staleness.days` exposes per-pair age so the
 * platform can alert on any pair whose freshness drifts outside its
 * expected cadence. Tagged with the canonical (alphabetically-ordered)
 * pair key.
 */
class CorrelationStalenessMetricTest : FunSpec({

    fun fixedClock(now: Instant) = Clock.fixed(now, ZoneId.of("UTC"))

    test("registers a gauge tagged with the canonical pair key") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = CorrelationStalenessMetric(registry, fixedClock(now))
        metric.observe("AAPL", "MSFT", lastUpdate = now.minusSeconds(2L * 86400))
        val g = registry.find("correlation.pair.staleness.days").tag("pair", "AAPL/MSFT").gauge()
        g!!.value() shouldBe 2.0
    }

    test("pair key is alphabetically ordered (symmetric query)") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = CorrelationStalenessMetric(registry, fixedClock(now))
        metric.observe("MSFT", "AAPL", now.minusSeconds(86400))
        val g = registry.find("correlation.pair.staleness.days").tag("pair", "AAPL/MSFT").gauge()
        g!!.value() shouldBe 1.0
    }

    test("multiple pairs register independently") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = CorrelationStalenessMetric(registry, fixedClock(now))
        metric.observe("AAPL", "MSFT", now.minusSeconds(86400))
        metric.observe("EUR", "USD", now.minusSeconds(7L * 86400))
        registry.find("correlation.pair.staleness.days").gauges().size shouldBe 2
    }

    test("re-observing the same pair updates without duplicating") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = CorrelationStalenessMetric(registry, fixedClock(now))
        metric.observe("AAPL", "MSFT", now.minusSeconds(86400))
        metric.observe("AAPL", "MSFT", now.minusSeconds(3L * 86400))
        val gauges = registry.find("correlation.pair.staleness.days").gauges()
        gauges.size shouldBe 1
        gauges.first().value() shouldBe 3.0
    }
})
