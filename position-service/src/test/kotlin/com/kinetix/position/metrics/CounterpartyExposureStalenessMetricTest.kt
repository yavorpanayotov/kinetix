package com.kinetix.position.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Counterparty exposure recalc cadence varies — major dealers
 * refresh continuously, secondary counterparties on a slower
 * schedule. Per-counterparty staleness gauge lets the platform alert
 * on a counterparty whose exposure value has gone unrefreshed beyond
 * its expected cadence.
 */
class CounterpartyExposureStalenessMetricTest : FunSpec({

    fun fixedClock(now: Instant) = Clock.fixed(now, ZoneId.of("UTC"))

    test("registers a gauge tagged with counterparty id") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = CounterpartyExposureStalenessMetric(registry, fixedClock(now))
        metric.observe("GS", now.minusSeconds(120))
        registry.find("position.counterparty.exposure.staleness.seconds")
            .tag("counterparty_id", "GS").gauge()!!.value() shouldBe 120.0
    }

    test("repeated observe replaces the value") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = CounterpartyExposureStalenessMetric(registry, fixedClock(now))
        metric.observe("GS", now.minusSeconds(30))
        metric.observe("GS", now.minusSeconds(300))
        registry.find("position.counterparty.exposure.staleness.seconds")
            .tag("counterparty_id", "GS").gauges().size shouldBe 1
        registry.find("position.counterparty.exposure.staleness.seconds")
            .tag("counterparty_id", "GS").gauge()!!.value() shouldBe 300.0
    }

    test("different counterparties register independently") {
        val now = Instant.parse("2026-05-28T12:00:00Z")
        val registry = SimpleMeterRegistry()
        val metric = CounterpartyExposureStalenessMetric(registry, fixedClock(now))
        metric.observe("GS", now.minusSeconds(30))
        metric.observe("MS", now.minusSeconds(60))
        registry.find("position.counterparty.exposure.staleness.seconds").gauges().size shouldBe 2
    }
})
