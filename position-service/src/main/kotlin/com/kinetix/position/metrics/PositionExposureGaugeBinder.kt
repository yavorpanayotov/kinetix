package com.kinetix.position.metrics

import com.kinetix.common.model.Position
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes exposure metrics for `position-service`, the owner of the position
 * book, so concentration and net-exposure dashboards can be driven directly
 * from the platform's Prometheus scrape.
 *
 * Two gauges are emitted:
 *
 *  - `position_notional{book_id,instrument_id,side}` — the absolute market-value
 *    notional of a single position. `side` is `LONG` for a positive quantity
 *    and `SHORT` for a negative one. Tagging by `side` (rather than emitting a
 *    signed value) keeps the metric non-negative and lets the dashboard derive
 *    everything it needs: net exposure is
 *    `sum(position_notional{side="LONG"}) - sum(position_notional{side="SHORT"})`,
 *    the long/short split is `sum by (side) (position_notional)`, and the
 *    top-N concentration is `topk(10, sum by (instrument_id) (position_notional))`.
 *  - `position_count{book_id}` — the number of open (non-flat) positions in a
 *    book.
 *
 * Flat positions (zero quantity) carry no exposure and are excluded from both
 * gauges; they would otherwise inflate `position_count` and register an
 * always-zero `position_notional` series with an arbitrary `side`.
 *
 * Each gauge is backed by an [AtomicReference] so [refresh] updates the reported
 * value in place rather than re-registering the meter. A position that is closed
 * between refreshes keeps its gauge registered but is driven to `0.0`, so the
 * series goes flat instead of vanishing mid-graph.
 */
class PositionExposureGaugeBinder(
    private val positionRepository: com.kinetix.position.persistence.PositionRepository,
    private val registry: MeterRegistry,
) {
    private val notionalGauges = ConcurrentHashMap<NotionalKey, AtomicReference<Double>>()
    private val countGauges = ConcurrentHashMap<String, AtomicReference<Double>>()

    /**
     * Reloads every book's positions from the repository and updates the
     * exposure gauges. Newly seen positions/books register a gauge; existing
     * ones have their value updated; positions no longer present are zeroed.
     */
    suspend fun refresh() {
        val openPositions = positionRepository.findDistinctBookIds()
            .flatMap { positionRepository.findByBookId(it) }
            .filter { it.quantity.signum() != 0 }

        refreshNotionalGauges(openPositions)
        refreshCountGauges(openPositions)
    }

    private fun refreshNotionalGauges(openPositions: List<Position>) {
        val seen = openPositions.associate { position ->
            val key = NotionalKey(
                bookId = position.bookId.value,
                instrumentId = position.instrumentId.value,
                side = sideOf(position),
            )
            key to position.marketValue.amount.abs().toDouble()
        }
        // Update or register a gauge for every currently-open position.
        seen.forEach { (key, value) ->
            notionalGauges.computeIfAbsent(key) { registerNotionalGauge(it) }.set(value)
        }
        // Drive any position that has since closed down to zero.
        notionalGauges.forEach { (key, ref) ->
            if (key !in seen) ref.set(0.0)
        }
    }

    private fun refreshCountGauges(openPositions: List<Position>) {
        val countsByBook = openPositions.groupingBy { it.bookId.value }.eachCount()
        countsByBook.forEach { (bookId, count) ->
            countGauges.computeIfAbsent(bookId) { registerCountGauge(it) }.set(count.toDouble())
        }
        countGauges.forEach { (bookId, ref) ->
            if (bookId !in countsByBook) ref.set(0.0)
        }
    }

    private fun registerNotionalGauge(key: NotionalKey): AtomicReference<Double> {
        val ref = AtomicReference(0.0)
        Gauge.builder("position_notional") { ref.get() }
            .description("Absolute market-value notional of an open position (owner: position-service)")
            .tag("book_id", key.bookId)
            .tag("instrument_id", key.instrumentId)
            .tag("side", key.side)
            .register(registry)
        return ref
    }

    private fun registerCountGauge(bookId: String): AtomicReference<Double> {
        val ref = AtomicReference(0.0)
        Gauge.builder("position_count") { ref.get() }
            .description("Number of open (non-flat) positions in a book (owner: position-service)")
            .tag("book_id", bookId)
            .register(registry)
        return ref
    }

    private fun sideOf(position: Position): String =
        if (position.quantity.signum() >= 0) SIDE_LONG else SIDE_SHORT

    private data class NotionalKey(
        val bookId: String,
        val instrumentId: String,
        val side: String,
    )

    companion object {
        /** `side` tag value for a long (positive-quantity) position. */
        const val SIDE_LONG = "LONG"

        /** `side` tag value for a short (negative-quantity) position. */
        const val SIDE_SHORT = "SHORT"
    }
}
