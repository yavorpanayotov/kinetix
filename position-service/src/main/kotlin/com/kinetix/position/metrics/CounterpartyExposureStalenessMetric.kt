package com.kinetix.position.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-counterparty exposure staleness:
 * `position.counterparty.exposure.staleness.seconds`. Major dealers
 * refresh exposure continuously; secondary counterparties update on
 * slower schedules. Per-counterparty gauges let the platform alert
 * when a counterparty's exposure value drifts outside its expected
 * cadence.
 */
class CounterpartyExposureStalenessMetric(
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    constructor(registry: MeterRegistry) : this(registry, Clock.systemUTC())

    private val lastUpdates: ConcurrentHashMap<String, Instant> = ConcurrentHashMap()
    private val registered: MutableSet<String> = mutableSetOf()

    fun observe(counterpartyId: String, lastUpdate: Instant) {
        lastUpdates[counterpartyId] = lastUpdate
        if (registered.add(counterpartyId)) {
            Gauge.builder(METRIC_NAME) { staleSeconds(counterpartyId) }
                .description("Seconds since the last exposure update for this counterparty")
                .tags(Tags.of("counterparty_id", counterpartyId))
                .baseUnit("seconds")
                .register(registry)
        }
    }

    private fun staleSeconds(counterpartyId: String): Double {
        val last = lastUpdates[counterpartyId] ?: return 0.0
        return (clock.instant().epochSecond - last.epochSecond).toDouble()
    }

    companion object {
        const val METRIC_NAME = "position.counterparty.exposure.staleness.seconds"
    }
}
