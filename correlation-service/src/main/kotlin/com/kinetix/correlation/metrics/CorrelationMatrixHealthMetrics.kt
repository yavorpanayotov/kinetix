package com.kinetix.correlation.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes surface-health metrics for `correlation-service` — the owner of
 * correlation matrices — so the shared `risk/surface-health.json` Grafana
 * dashboard can be driven directly from the platform's Prometheus scrape.
 *
 * Three meters are emitted, all tagged by `matrix_id` (an identifier for the
 * matrix; the correlation feed maintains a single dense matrix, so a stable
 * label such as `"default"` groups its updates):
 *
 *  - `correlation_matrix_last_update_timestamp_seconds{matrix_id}` — Gauge, the
 *    Unix epoch (in seconds) at which the matrix was last (re)built. The
 *    dashboard converts this to a "last-update age" with
 *    `time() - <metric>`; a stale matrix shows a large, growing age.
 *  - `correlation_matrix_points{matrix_id}` — Gauge, the number of distinct
 *    pairwise correlation entries on the matrix as last built — its size. An
 *    `n×n` matrix has `n²` entries; a matrix that loses labels has degraded
 *    coverage.
 *  - `correlation_matrix_calibration_failures_total{matrix_id}` — Counter,
 *    incremented once each time a matrix (re)build fails to calibrate (a matrix
 *    that is not positive-semi-definite, or an error raised while
 *    persisting/caching/publishing it). A rising count means the feed is
 *    producing matrices the service cannot accept.
 *
 * The two per-matrix gauges are backed by [AtomicReference]s so a later matrix
 * update for the same id refreshes the reported value in place rather than
 * re-registering the meter.
 *
 * All meters are registered lazily on first use against the supplied
 * [MeterRegistry], which in production is the application's
 * `PrometheusMeterRegistry`.
 */
class CorrelationMatrixHealthMetrics(
    private val registry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lastUpdateGauges = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val pointCountGauges = ConcurrentHashMap<String, AtomicReference<Double>>()

    /**
     * Records a successful (re)build of the correlation matrix [matrixId] with
     * [pointCount] entries. Refreshes the last-update timestamp gauge to the
     * current time and the point-count gauge to [pointCount].
     */
    fun recordMatrixUpdate(matrixId: String, pointCount: Int) {
        lastUpdateGauge(matrixId).set(clock.instant().epochSecond.toDouble())
        pointCountGauge(matrixId).set(pointCount.toDouble())
    }

    /**
     * Records that a correlation-matrix (re)build for [matrixId] failed to
     * calibrate. Increments the per-matrix calibration-failure counter.
     */
    fun recordCalibrationFailure(matrixId: String) {
        registry.counter(CALIBRATION_FAILURES_TOTAL, "matrix_id", matrixId).increment()
    }

    private fun lastUpdateGauge(matrixId: String): AtomicReference<Double> =
        lastUpdateGauges.computeIfAbsent(matrixId) {
            val ref = AtomicReference(0.0)
            Gauge.builder(LAST_UPDATE_TIMESTAMP) { ref.get() }
                .description("Unix epoch seconds of the last correlation-matrix (re)build (owner: correlation-service)")
                .tag("matrix_id", it)
                .register(registry)
            ref
        }

    private fun pointCountGauge(matrixId: String): AtomicReference<Double> =
        pointCountGauges.computeIfAbsent(matrixId) {
            val ref = AtomicReference(0.0)
            Gauge.builder(MATRIX_POINTS) { ref.get() }
                .description("Number of entries on the latest correlation matrix (owner: correlation-service)")
                .tag("matrix_id", it)
                .register(registry)
            ref
        }

    companion object {
        const val LAST_UPDATE_TIMESTAMP = "correlation_matrix_last_update_timestamp_seconds"
        const val MATRIX_POINTS = "correlation_matrix_points"
        const val CALIBRATION_FAILURES_TOTAL = "correlation_matrix_calibration_failures_total"

        /** Stable id for the single dense matrix maintained by the correlation feed. */
        const val DEFAULT_MATRIX_ID = "default"
    }
}
