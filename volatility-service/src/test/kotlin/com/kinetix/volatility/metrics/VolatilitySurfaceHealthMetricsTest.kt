package com.kinetix.volatility.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Instrumentation contract for the surface-health metrics that drive the
 * `risk/surface-health.json` Grafana dashboard (checkbox 4.5 of
 * plans/grafana-v2.md):
 *
 *   - volatility_surface_last_update_timestamp_seconds{underlying}  Gauge
 *   - volatility_surface_points{underlying}                        Gauge
 *   - volatility_surface_calibration_failures_total{underlying}     Counter
 *
 * Each metric is asserted both as a Micrometer meter of the expected type and,
 * where the Prometheus wire name matters for the dashboard's PromQL, via a real
 * [PrometheusMeterRegistry] `.scrape()`.
 */
class VolatilitySurfaceHealthMetricsTest : FunSpec({

    fun fixedClock(epochSeconds: Long): Clock =
        Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)

    /** A clock whose instant the test can advance between calls. */
    class MutableClock(var epochSeconds: Long) : Clock() {
        override fun instant(): Instant = Instant.ofEpochSecond(epochSeconds)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    // ---------------------------------------------------------------------
    // volatility_surface_last_update_timestamp_seconds — Gauge (underlying)
    // ---------------------------------------------------------------------

    test("recordSurfaceUpdate registers a last-update timestamp gauge for the underlying") {
        val registry = SimpleMeterRegistry()
        VolatilitySurfaceHealthMetrics(registry, fixedClock(1_700_000_000))
            .recordSurfaceUpdate("AAPL", pointCount = 12)

        val meter = registry.find("volatility_surface_last_update_timestamp_seconds")
            .tag("underlying", "AAPL")
            .meter()
        meter shouldNotBe null
        (meter is Gauge) shouldBe true
    }

    test("recordSurfaceUpdate sets the last-update timestamp to the clock time") {
        val registry = SimpleMeterRegistry()
        VolatilitySurfaceHealthMetrics(registry, fixedClock(1_700_000_000))
            .recordSurfaceUpdate("AAPL", pointCount = 12)

        registry.find("volatility_surface_last_update_timestamp_seconds")
            .tag("underlying", "AAPL")
            .gauge()!!
            .value() shouldBe 1_700_000_000.0
    }

    test("a later surface update advances the last-update timestamp gauge in place") {
        val registry = SimpleMeterRegistry()
        val clock = MutableClock(1_700_000_500)
        val metrics = VolatilitySurfaceHealthMetrics(registry, clock)
        metrics.recordSurfaceUpdate("AAPL", pointCount = 12)

        // The next feed tick lands later.
        clock.epochSeconds = 1_700_000_900
        metrics.recordSurfaceUpdate("AAPL", pointCount = 12)

        registry.find("volatility_surface_last_update_timestamp_seconds")
            .tag("underlying", "AAPL")
            .gauge()!!
            .value() shouldBe 1_700_000_900.0
    }

    // ---------------------------------------------------------------------
    // volatility_surface_points — Gauge (underlying)
    // ---------------------------------------------------------------------

    test("recordSurfaceUpdate registers a point-count gauge for the underlying") {
        val registry = SimpleMeterRegistry()
        VolatilitySurfaceHealthMetrics(registry).recordSurfaceUpdate("AAPL", pointCount = 25)

        val meter = registry.find("volatility_surface_points").tag("underlying", "AAPL").meter()
        meter shouldNotBe null
        (meter is Gauge) shouldBe true
    }

    test("recordSurfaceUpdate sets the point-count gauge to the surface size") {
        val registry = SimpleMeterRegistry()
        VolatilitySurfaceHealthMetrics(registry).recordSurfaceUpdate("AAPL", pointCount = 25)

        registry.find("volatility_surface_points").tag("underlying", "AAPL").gauge()!!
            .value() shouldBe 25.0
    }

    test("a later surface update refreshes the point-count gauge in place") {
        val registry = SimpleMeterRegistry()
        val metrics = VolatilitySurfaceHealthMetrics(registry)
        metrics.recordSurfaceUpdate("AAPL", pointCount = 25)
        metrics.recordSurfaceUpdate("AAPL", pointCount = 30)

        registry.find("volatility_surface_points").tag("underlying", "AAPL").gauge()!!
            .value() shouldBe 30.0
    }

    test("point-count gauge is tracked per underlying") {
        val registry = SimpleMeterRegistry()
        val metrics = VolatilitySurfaceHealthMetrics(registry)
        metrics.recordSurfaceUpdate("AAPL", pointCount = 25)
        metrics.recordSurfaceUpdate("TSLA", pointCount = 9)

        registry.find("volatility_surface_points").tag("underlying", "AAPL").gauge()!!.value() shouldBe 25.0
        registry.find("volatility_surface_points").tag("underlying", "TSLA").gauge()!!.value() shouldBe 9.0
    }

    // ---------------------------------------------------------------------
    // volatility_surface_calibration_failures_total — Counter (underlying)
    // ---------------------------------------------------------------------

    test("recordCalibrationFailure registers a calibration-failure counter for the underlying") {
        val registry = SimpleMeterRegistry()
        VolatilitySurfaceHealthMetrics(registry).recordCalibrationFailure("AAPL")

        val meter = registry.find("volatility_surface_calibration_failures_total")
            .tag("underlying", "AAPL")
            .meter()
        meter shouldNotBe null
        (meter is Counter) shouldBe true
    }

    test("recordCalibrationFailure increments the calibration-failure counter") {
        val registry = SimpleMeterRegistry()
        val metrics = VolatilitySurfaceHealthMetrics(registry)
        metrics.recordCalibrationFailure("AAPL")
        metrics.recordCalibrationFailure("AAPL")

        registry.counter("volatility_surface_calibration_failures_total", "underlying", "AAPL")
            .count() shouldBe 2.0
    }

    test("calibration-failure counter is tracked per underlying") {
        val registry = SimpleMeterRegistry()
        val metrics = VolatilitySurfaceHealthMetrics(registry)
        metrics.recordCalibrationFailure("AAPL")
        metrics.recordCalibrationFailure("TSLA")
        metrics.recordCalibrationFailure("TSLA")

        registry.counter("volatility_surface_calibration_failures_total", "underlying", "AAPL").count() shouldBe 1.0
        registry.counter("volatility_surface_calibration_failures_total", "underlying", "TSLA").count() shouldBe 2.0
    }

    // ---------------------------------------------------------------------
    // Prometheus wire-format names — the dashboard PromQL must match exactly
    // ---------------------------------------------------------------------

    test("all surface-health metrics scrape under their expected Prometheus names") {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metrics = VolatilitySurfaceHealthMetrics(registry, fixedClock(1_700_000_000))
        metrics.recordSurfaceUpdate("AAPL", pointCount = 12)
        metrics.recordCalibrationFailure("TSLA")

        val scrape = registry.scrape()
        scrape shouldContain "volatility_surface_last_update_timestamp_seconds{"
        scrape shouldContain "volatility_surface_points{"
        scrape shouldContain "volatility_surface_calibration_failures_total{"
    }
})
