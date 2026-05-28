package com.kinetix.volatility.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class VolSurfaceStalenessMetricTest : FunSpec({

    fun fixedClock(now: Instant) = Clock.fixed(now, ZoneId.of("UTC"))

    test("registers a gauge per underlying tag") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = VolSurfaceStalenessMetric(registry, fixedClock(now))
        metric.observe("SPX", lastUpdate = now.minusSeconds(30))
        val g = registry.find(VolSurfaceStalenessMetric.METRIC_NAME).tag("underlying", "SPX").gauge()
        g!!.value() shouldBe 30.0
    }

    test("re-observing updates the gauge without duplicating it") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = VolSurfaceStalenessMetric(registry, fixedClock(now))
        metric.observe("SPX", now.minusSeconds(30))
        metric.observe("SPX", now.minusSeconds(60))
        val gauges = registry.find(VolSurfaceStalenessMetric.METRIC_NAME).tag("underlying", "SPX").gauges()
        gauges.size shouldBe 1
        gauges.first().value() shouldBe 60.0
    }

    test("multiple underlyings register independently") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = VolSurfaceStalenessMetric(registry, fixedClock(now))
        metric.observe("SPX", now.minusSeconds(30))
        metric.observe("AAPL", now.minusSeconds(300))
        val gauges = registry.find(VolSurfaceStalenessMetric.METRIC_NAME).gauges()
        gauges.size shouldBe 2
    }

    test("a never-updated surface (null) publishes a large positive sentinel") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = VolSurfaceStalenessMetric(registry, fixedClock(now))
        metric.observe("NEW", lastUpdate = null)
        val g = registry.find(VolSurfaceStalenessMetric.METRIC_NAME).tag("underlying", "NEW").gauge()
        g!!.value() shouldBeGreaterThan 1e9
    }
})
