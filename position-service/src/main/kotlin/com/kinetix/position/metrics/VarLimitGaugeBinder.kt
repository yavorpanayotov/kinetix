package com.kinetix.position.metrics

import com.kinetix.position.model.LimitType
import com.kinetix.position.persistence.LimitDefinitionRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes a `risk_var_limit{book_id,calculation_type,confidence_level}` gauge
 * reflecting each book's configured VaR limit.
 *
 * `position-service` owns limits, so it is the authoritative source for the
 * limit side of VaR-utilisation dashboards: utilisation is
 * `risk_var_value / risk_var_limit` joined on `book_id`, where `risk_var_value`
 * is produced by risk-orchestrator/risk-engine.
 *
 * The gauge value is read from a [LimitDefinition] of type [LimitType.VAR].
 * A configured VaR limit is method- and confidence-agnostic — it is not stored
 * per calculation method or confidence level — so `calculation_type` and
 * `confidence_level` are tagged with [LIMIT_DIMENSION_ALL]. This keeps the
 * metric self-describing and label-compatible with `risk_var_value` while making
 * explicit that the limit applies across all VaR methods and confidence levels.
 *
 * Each gauge is backed by an [AtomicReference] so [refresh] updates the reported
 * value in place rather than re-registering the meter.
 */
class VarLimitGaugeBinder(
    private val limitDefinitionRepository: LimitDefinitionRepository,
    private val registry: MeterRegistry,
) {
    private val gaugeValues = ConcurrentHashMap<String, AtomicReference<Double>>()

    /**
     * Reloads book VaR limits from the repository and updates the gauges.
     * Newly seen books register a gauge; existing ones have their value updated.
     */
    suspend fun refresh() {
        limitDefinitionRepository.findAll()
            .filter { it.active && it.limitType == LimitType.VAR }
            .forEach { limit ->
                val bookId = limit.entityId
                val value = limit.limitValue.toDouble()
                val ref = gaugeValues.computeIfAbsent(bookId) { key ->
                    val atomicRef = AtomicReference(value)
                    Gauge.builder("risk_var_limit") { atomicRef.get() }
                        .description("Configured VaR limit per book (limit owner: position-service)")
                        .tag("book_id", key)
                        .tag("calculation_type", LIMIT_DIMENSION_ALL)
                        .tag("confidence_level", LIMIT_DIMENSION_ALL)
                        .register(registry)
                    atomicRef
                }
                ref.set(value)
            }
    }

    companion object {
        /**
         * Tag value used for `calculation_type` / `confidence_level` on
         * `risk_var_limit`: a configured VaR limit applies across every VaR
         * calculation method and confidence level.
         */
        const val LIMIT_DIMENSION_ALL = "ALL"
    }
}
