package com.kinetix.price.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Liquid instruments tick every few hundred ms; illiquid ones can sit
 * for hours. A per-instrument staleness gauge
 * (`price.staleness.seconds`) lets the platform alert per tier
 * without false alarms.
 */
class PriceStalenessMetricTest : FunSpec({

    fun fixedClock(now: Instant) = Clock.fixed(now, ZoneId.of("UTC"))

    test("registers a gauge tagged with the instrument") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = PriceStalenessMetric(registry, fixedClock(now))
        metric.observe("AAPL", now.minusSeconds(5))
        registry.find("price.staleness.seconds").tag("instrument", "AAPL").gauge()!!.value() shouldBe 5.0
    }

    test("re-observing updates without duplicating") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = PriceStalenessMetric(registry, fixedClock(now))
        metric.observe("AAPL", now.minusSeconds(5))
        metric.observe("AAPL", now.minusSeconds(60))
        registry.find("price.staleness.seconds").tag("instrument", "AAPL").gauges().size shouldBe 1
    }

    test("multiple instruments register independently") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = PriceStalenessMetric(registry, fixedClock(now))
        metric.observe("AAPL", now.minusSeconds(5))
        metric.observe("MSFT", now.minusSeconds(60))
        registry.find("price.staleness.seconds").gauges().size shouldBe 2
    }
})
