package com.kinetix.risk.orchestrator.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Histogram of Greeks-calculation latency tagged with the asset class
 * being computed. Compute latency is heterogeneous — a fixed-income
 * book with thousands of positions takes much longer than a flat
 * equity-only book — and a single aggregate timer hides the slow tier.
 * Per-asset-class timers let the platform alert on a slow asset class
 * without false alarms from the fast one.
 */
class GreeksLatencyHistogram(private val registry: MeterRegistry) {

    private val timers: ConcurrentHashMap<String, Timer> = ConcurrentHashMap()

    fun recordSample(assetClass: String, duration: Duration) {
        timers.computeIfAbsent(assetClass) {
            Timer.builder(METRIC_NAME)
                .description("Latency of a Greeks recalc, per asset class")
                .tags(Tags.of("asset_class", assetClass))
                .register(registry)
        }.record(duration)
    }

    companion object {
        const val METRIC_NAME = "risk.orchestrator.greeks.compute.latency"
    }
}
