package com.kinetix.risk.orchestrator.concentration

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Publishes the largest-single-position weight per portfolio as a
 * Micrometer gauge, named `risk.orchestrator.portfolio.concentration.max_weight`,
 * tagged with the portfolio id. The trader sitting on a 60% AAPL book
 * shows up differently from one with 5% across 20 names — the on-call
 * risk officer sees that at a glance.
 *
 * The weight is computed from absolute notional, so a single -90% short
 * counts the same as a single +90% long.
 */
class PortfolioConcentrationGauge(private val registry: MeterRegistry) {
    private val latest: ConcurrentHashMap<String, Double> = ConcurrentHashMap()
    private val registered: MutableSet<String> = mutableSetOf()

    fun observe(portfolioId: String, positionNotionals: Map<String, Double>) {
        val gross = positionNotionals.values.sumOf { abs(it) }
        val maxAbs = positionNotionals.values.maxOfOrNull { abs(it) } ?: 0.0
        val weight = if (gross == 0.0) 0.0 else maxAbs / gross
        latest[portfolioId] = weight
        if (registered.add(portfolioId)) {
            Gauge.builder(METRIC_NAME) { latest[portfolioId] ?: 0.0 }
                .description("Largest single-position weight in the portfolio")
                .tags(Tags.of("portfolio_id", portfolioId))
                .register(registry)
        }
    }

    companion object {
        const val METRIC_NAME = "risk.orchestrator.portfolio.concentration.max_weight"
    }
}
