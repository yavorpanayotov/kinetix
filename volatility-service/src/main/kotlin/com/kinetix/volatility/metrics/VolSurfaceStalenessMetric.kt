package com.kinetix.volatility.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-underlying vol-surface staleness gauge:
 * `volatility.surface.staleness.seconds`. Different surfaces refresh
 * at different cadences (SPX tick-by-tick; exotic single names
 * daily), so per-underlying gauges let the platform set tier-
 * specific alert thresholds without false alarms.
 *
 * Never-updated surfaces publish a sentinel 1e10 so the alerting
 * rule trips on the first scrape rather than silently treating an
 * unscheduled surface as "fresh".
 */
class VolSurfaceStalenessMetric(
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    constructor(registry: MeterRegistry) : this(registry, Clock.systemUTC())

    // ConcurrentHashMap rejects null values, so a "never updated" state is
    // represented by absence from this map.
    private val lastUpdates: ConcurrentHashMap<String, Instant> = ConcurrentHashMap()
    private val registered: MutableSet<String> = mutableSetOf()

    fun observe(underlying: String, lastUpdate: Instant?) {
        if (lastUpdate == null) {
            lastUpdates.remove(underlying)
        } else {
            lastUpdates[underlying] = lastUpdate
        }
        if (registered.add(underlying)) {
            Gauge.builder(METRIC_NAME) { staleSeconds(underlying) }
                .description("Seconds since the vol surface for this underlying was last updated")
                .tags(Tags.of("underlying", underlying))
                .baseUnit("seconds")
                .register(registry)
        }
    }

    private fun staleSeconds(underlying: String): Double {
        val last = lastUpdates[underlying] ?: return NEVER_UPDATED_SENTINEL
        return (clock.instant().epochSecond - last.epochSecond).toDouble()
    }

    companion object {
        const val METRIC_NAME = "volatility.surface.staleness.seconds"
        private const val NEVER_UPDATED_SENTINEL = 1.0e10
    }
}
