package com.kinetix.price.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-instrument price staleness gauge: `price.staleness.seconds`.
 * Liquid instruments tick every few hundred ms; illiquid ones can sit
 * for hours. A per-instrument gauge lets the platform alert per tier
 * (TIER_1 names get a 30s threshold; ILLIQUID get a multi-hour one).
 */
class PriceStalenessMetric(
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    constructor(registry: MeterRegistry) : this(registry, Clock.systemUTC())

    private val lastUpdates: ConcurrentHashMap<String, Instant> = ConcurrentHashMap()
    private val registered: MutableSet<String> = mutableSetOf()

    fun observe(instrumentId: String, lastUpdate: Instant) {
        lastUpdates[instrumentId] = lastUpdate
        if (registered.add(instrumentId)) {
            Gauge.builder(METRIC_NAME) { staleSeconds(instrumentId) }
                .description("Seconds since the last price update for this instrument")
                .tags(Tags.of("instrument", instrumentId))
                .baseUnit("seconds")
                .register(registry)
        }
    }

    private fun staleSeconds(instrumentId: String): Double {
        val last = lastUpdates[instrumentId] ?: return 0.0
        return (clock.instant().epochSecond - last.epochSecond).toDouble()
    }

    companion object {
        const val METRIC_NAME = "price.staleness.seconds"
    }
}
