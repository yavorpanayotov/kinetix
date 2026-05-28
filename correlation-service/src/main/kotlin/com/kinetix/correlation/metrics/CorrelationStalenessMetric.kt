package com.kinetix.correlation.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Publishes a per-pair `correlation.pair.staleness.days` gauge so the
 * platform can alert when a correlation feed drifts outside its
 * expected cadence (equities daily; exotic cross-rates weekly). The
 * pair tag is canonicalised by alphabetical order so query symmetry
 * holds: observe("AAPL","MSFT") and observe("MSFT","AAPL") map to the
 * same gauge.
 */
class CorrelationStalenessMetric(
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    constructor(registry: MeterRegistry) : this(registry, Clock.systemUTC())

    private val lastUpdates: ConcurrentHashMap<String, Instant> = ConcurrentHashMap()
    private val registered: MutableSet<String> = mutableSetOf()

    fun observe(a: String, b: String, lastUpdate: Instant) {
        val key = canonical(a, b)
        lastUpdates[key] = lastUpdate
        if (registered.add(key)) {
            Gauge.builder(METRIC_NAME) { staleDays(key) }
                .description("Days since the correlation between this pair was last updated")
                .tags(Tags.of("pair", key))
                .baseUnit("days")
                .register(registry)
        }
    }

    private fun staleDays(pair: String): Double {
        val last = lastUpdates[pair] ?: return 0.0
        val seconds = clock.instant().epochSecond - last.epochSecond
        return seconds.toDouble() / SECONDS_PER_DAY
    }

    private fun canonical(a: String, b: String): String =
        if (a <= b) "$a/$b" else "$b/$a"

    companion object {
        const val METRIC_NAME = "correlation.pair.staleness.days"
        private const val SECONDS_PER_DAY: Double = 86_400.0
    }
}
