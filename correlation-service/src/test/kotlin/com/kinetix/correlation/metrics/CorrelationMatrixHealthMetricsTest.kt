package com.kinetix.correlation.metrics

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
 * docs/plans/grafana-v2.md):
 *
 *   - correlation_matrix_last_update_timestamp_seconds{matrix_id}  Gauge
 *   - correlation_matrix_points{matrix_id}                         Gauge
 *   - correlation_matrix_calibration_failures_total{matrix_id}      Counter
 *
 * Each metric is asserted both as a Micrometer meter of the expected type and,
 * where the Prometheus wire name matters for the dashboard's PromQL, via a real
 * [PrometheusMeterRegistry] `.scrape()`.
 */
class CorrelationMatrixHealthMetricsTest : FunSpec({

    fun fixedClock(epochSeconds: Long): Clock =
        Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)

    /** A clock whose instant the test can advance between calls. */
    class MutableClock(var epochSeconds: Long) : Clock() {
        override fun instant(): Instant = Instant.ofEpochSecond(epochSeconds)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    // ---------------------------------------------------------------------
    // correlation_matrix_last_update_timestamp_seconds — Gauge (matrix_id)
    // ---------------------------------------------------------------------

    test("recordMatrixUpdate registers a last-update timestamp gauge for the matrix") {
        val registry = SimpleMeterRegistry()
        CorrelationMatrixHealthMetrics(registry, fixedClock(1_700_000_000))
            .recordMatrixUpdate("default", pointCount = 16)

        val meter = registry.find("correlation_matrix_last_update_timestamp_seconds")
            .tag("matrix_id", "default")
            .meter()
        meter shouldNotBe null
        (meter is Gauge) shouldBe true
    }

    test("recordMatrixUpdate sets the last-update timestamp to the clock time") {
        val registry = SimpleMeterRegistry()
        CorrelationMatrixHealthMetrics(registry, fixedClock(1_700_000_000))
            .recordMatrixUpdate("default", pointCount = 16)

        registry.find("correlation_matrix_last_update_timestamp_seconds")
            .tag("matrix_id", "default")
            .gauge()!!
            .value() shouldBe 1_700_000_000.0
    }

    test("a later matrix update advances the last-update timestamp gauge in place") {
        val registry = SimpleMeterRegistry()
        val clock = MutableClock(1_700_000_500)
        val metrics = CorrelationMatrixHealthMetrics(registry, clock)
        metrics.recordMatrixUpdate("default", pointCount = 16)

        // The next feed tick lands later.
        clock.epochSeconds = 1_700_000_900
        metrics.recordMatrixUpdate("default", pointCount = 16)

        registry.find("correlation_matrix_last_update_timestamp_seconds")
            .tag("matrix_id", "default")
            .gauge()!!
            .value() shouldBe 1_700_000_900.0
    }

    // ---------------------------------------------------------------------
    // correlation_matrix_points — Gauge (matrix_id)
    // ---------------------------------------------------------------------

    test("recordMatrixUpdate registers a point-count gauge for the matrix") {
        val registry = SimpleMeterRegistry()
        CorrelationMatrixHealthMetrics(registry).recordMatrixUpdate("default", pointCount = 36)

        val meter = registry.find("correlation_matrix_points").tag("matrix_id", "default").meter()
        meter shouldNotBe null
        (meter is Gauge) shouldBe true
    }

    test("recordMatrixUpdate sets the point-count gauge to the matrix size") {
        val registry = SimpleMeterRegistry()
        CorrelationMatrixHealthMetrics(registry).recordMatrixUpdate("default", pointCount = 36)

        registry.find("correlation_matrix_points").tag("matrix_id", "default").gauge()!!
            .value() shouldBe 36.0
    }

    test("a later matrix update refreshes the point-count gauge in place") {
        val registry = SimpleMeterRegistry()
        val metrics = CorrelationMatrixHealthMetrics(registry)
        metrics.recordMatrixUpdate("default", pointCount = 36)
        metrics.recordMatrixUpdate("default", pointCount = 49)

        registry.find("correlation_matrix_points").tag("matrix_id", "default").gauge()!!
            .value() shouldBe 49.0
    }

    test("point-count gauge is tracked per matrix id") {
        val registry = SimpleMeterRegistry()
        val metrics = CorrelationMatrixHealthMetrics(registry)
        metrics.recordMatrixUpdate("equities", pointCount = 36)
        metrics.recordMatrixUpdate("rates", pointCount = 9)

        registry.find("correlation_matrix_points").tag("matrix_id", "equities").gauge()!!.value() shouldBe 36.0
        registry.find("correlation_matrix_points").tag("matrix_id", "rates").gauge()!!.value() shouldBe 9.0
    }

    // ---------------------------------------------------------------------
    // correlation_matrix_calibration_failures_total — Counter (matrix_id)
    // ---------------------------------------------------------------------

    test("recordCalibrationFailure registers a calibration-failure counter for the matrix") {
        val registry = SimpleMeterRegistry()
        CorrelationMatrixHealthMetrics(registry).recordCalibrationFailure("default")

        val meter = registry.find("correlation_matrix_calibration_failures_total")
            .tag("matrix_id", "default")
            .meter()
        meter shouldNotBe null
        (meter is Counter) shouldBe true
    }

    test("recordCalibrationFailure increments the calibration-failure counter") {
        val registry = SimpleMeterRegistry()
        val metrics = CorrelationMatrixHealthMetrics(registry)
        metrics.recordCalibrationFailure("default")
        metrics.recordCalibrationFailure("default")

        registry.counter("correlation_matrix_calibration_failures_total", "matrix_id", "default")
            .count() shouldBe 2.0
    }

    test("calibration-failure counter is tracked per matrix id") {
        val registry = SimpleMeterRegistry()
        val metrics = CorrelationMatrixHealthMetrics(registry)
        metrics.recordCalibrationFailure("equities")
        metrics.recordCalibrationFailure("rates")
        metrics.recordCalibrationFailure("rates")

        registry.counter("correlation_matrix_calibration_failures_total", "matrix_id", "equities").count() shouldBe 1.0
        registry.counter("correlation_matrix_calibration_failures_total", "matrix_id", "rates").count() shouldBe 2.0
    }

    // ---------------------------------------------------------------------
    // Prometheus wire-format names — the dashboard PromQL must match exactly
    // ---------------------------------------------------------------------

    test("all surface-health metrics scrape under their expected Prometheus names") {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metrics = CorrelationMatrixHealthMetrics(registry, fixedClock(1_700_000_000))
        metrics.recordMatrixUpdate("default", pointCount = 16)
        metrics.recordCalibrationFailure("default")

        val scrape = registry.scrape()
        scrape shouldContain "correlation_matrix_last_update_timestamp_seconds{"
        scrape shouldContain "correlation_matrix_points{"
        scrape shouldContain "correlation_matrix_calibration_failures_total{"
    }
})
