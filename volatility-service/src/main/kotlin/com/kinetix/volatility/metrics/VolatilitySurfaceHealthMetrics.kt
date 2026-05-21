package com.kinetix.volatility.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes surface-health metrics for `volatility-service` — the owner of
 * implied-volatility surfaces — so the shared `risk/surface-health.json` Grafana
 * dashboard can be driven directly from the platform's Prometheus scrape.
 *
 * Three meters are emitted, all tagged by `underlying` (the surface's instrument
 * id):
 *
 *  - `volatility_surface_last_update_timestamp_seconds{underlying}` — Gauge, the
 *    Unix epoch (in seconds) at which the surface was last (re)built. The
 *    dashboard converts this to a "last-update age" with
 *    `time() - <metric>`; a stale surface shows a large, growing age.
 *  - `volatility_surface_points{underlying}` — Gauge, the number of vol points
 *    on the surface as last built — its grid density. A surface that loses
 *    points has degraded coverage.
 *  - `volatility_surface_calibration_failures_total{underlying}` — Counter,
 *    incremented once each time a surface (re)build fails (a calibration error
 *    raised while persisting/caching/publishing the surface). A rising count
 *    means the feed is producing surfaces the service cannot accept.
 *
 * The two per-underlying gauges are backed by [AtomicReference]s so a later
 * surface update for the same underlying refreshes the reported value in place
 * rather than re-registering the meter.
 *
 * All meters are registered lazily on first use against the supplied
 * [MeterRegistry], which in production is the application's
 * `PrometheusMeterRegistry`.
 */
class VolatilitySurfaceHealthMetrics(
    private val registry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lastUpdateGauges = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val pointCountGauges = ConcurrentHashMap<String, AtomicReference<Double>>()

    /**
     * Records a successful (re)build of the vol surface for [underlying] with
     * [pointCount] points. Refreshes the last-update timestamp gauge to the
     * current time and the point-count gauge to [pointCount].
     */
    fun recordSurfaceUpdate(underlying: String, pointCount: Int) {
        lastUpdateGauge(underlying).set(clock.instant().epochSecond.toDouble())
        pointCountGauge(underlying).set(pointCount.toDouble())
    }

    /**
     * Records that a vol-surface (re)build for [underlying] failed to calibrate.
     * Increments the per-underlying calibration-failure counter.
     */
    fun recordCalibrationFailure(underlying: String) {
        registry.counter(CALIBRATION_FAILURES_TOTAL, "underlying", underlying).increment()
    }

    private fun lastUpdateGauge(underlying: String): AtomicReference<Double> =
        lastUpdateGauges.computeIfAbsent(underlying) {
            val ref = AtomicReference(0.0)
            Gauge.builder(LAST_UPDATE_TIMESTAMP) { ref.get() }
                .description("Unix epoch seconds of the last vol-surface (re)build (owner: volatility-service)")
                .tag("underlying", it)
                .register(registry)
            ref
        }

    private fun pointCountGauge(underlying: String): AtomicReference<Double> =
        pointCountGauges.computeIfAbsent(underlying) {
            val ref = AtomicReference(0.0)
            Gauge.builder(SURFACE_POINTS) { ref.get() }
                .description("Number of points on the latest vol surface (owner: volatility-service)")
                .tag("underlying", it)
                .register(registry)
            ref
        }

    companion object {
        const val LAST_UPDATE_TIMESTAMP = "volatility_surface_last_update_timestamp_seconds"
        const val SURFACE_POINTS = "volatility_surface_points"
        const val CALIBRATION_FAILURES_TOTAL = "volatility_surface_calibration_failures_total"
    }
}
