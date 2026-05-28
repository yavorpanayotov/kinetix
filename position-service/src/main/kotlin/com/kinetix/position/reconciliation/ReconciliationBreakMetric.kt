package com.kinetix.position.reconciliation

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.util.concurrent.ConcurrentHashMap

/**
 * Counter for failed reconciliation breaks, sliced by severity tag.
 * `position.reconciliation.break.count`. CRITICAL/WARNING/INFO let
 * the platform alert on critical breaks without burying them under
 * cosmetic noise.
 */
class ReconciliationBreakMetric(private val registry: MeterRegistry) {

    private val counters: ConcurrentHashMap<String, Counter> = ConcurrentHashMap()

    fun record(severity: String) {
        counters.computeIfAbsent(severity) {
            Counter.builder(METRIC_NAME)
                .description("Failed reconciliation breaks by severity")
                .tags(Tags.of("severity", severity))
                .register(registry)
        }.increment()
    }

    companion object {
        const val METRIC_NAME = "position.reconciliation.break.count"
    }
}
