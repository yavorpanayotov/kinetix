package com.kinetix.risk.orchestrator.metrics

import com.kinetix.risk.model.PnlAttribution
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes a book's daily P&L attribution as Prometheus gauges so the Grafana
 * "P&L" dashboard resolves (kx-wrvc).
 *
 * The dashboard queries `pnl_attribution_total_pnl`, `pnl_attribution_unexplained_pnl`
 * and `pnl_attribution_greek_pnl{greek=...}`. Those gauges were only ever set
 * inside the risk-engine's legacy `CalculateVaR` gRPC handler (superseded by the
 * unified `Valuate` RPC, ADR-0024) and its factor server — neither of which the
 * running platform drives — so the series never existed and every P&L panel read
 * "No data". The attribution itself is already computed and persisted by the
 * orchestrator (it backs the P&L tab), so here we simply mirror the persisted
 * numbers onto gauges the registry already scrapes.
 *
 * Each (book, greek) series is backed by an [AtomicReference] holder registered
 * once and updated in place, so repeated [publish] calls re-point the existing
 * gauge rather than registering duplicates.
 */
class PnlAttributionMetrics(private val registry: MeterRegistry) {

    private val totalPnl = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val unexplainedPnl = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val greekPnl = ConcurrentHashMap<String, AtomicReference<Double>>()

    fun publish(attribution: PnlAttribution) {
        val book = attribution.bookId.value
        holder(totalPnl, "pnl_attribution_total_pnl", book).set(attribution.totalPnl.toDouble())
        holder(unexplainedPnl, "pnl_attribution_unexplained_pnl", book).set(attribution.unexplainedPnl.toDouble())

        greekHolder(book, "delta").set(attribution.deltaPnl.toDouble())
        greekHolder(book, "gamma").set(attribution.gammaPnl.toDouble())
        greekHolder(book, "vega").set(attribution.vegaPnl.toDouble())
        greekHolder(book, "theta").set(attribution.thetaPnl.toDouble())
        greekHolder(book, "rho").set(attribution.rhoPnl.toDouble())
    }

    private fun holder(
        map: ConcurrentHashMap<String, AtomicReference<Double>>,
        metricName: String,
        book: String,
    ): AtomicReference<Double> =
        map.computeIfAbsent(book) {
            val ref = AtomicReference(0.0)
            Gauge.builder(metricName, ref) { it.get() }
                .tag("book_id", book)
                .register(registry)
            ref
        }

    private fun greekHolder(book: String, greek: String): AtomicReference<Double> =
        greekPnl.computeIfAbsent("$book|$greek") {
            val ref = AtomicReference(0.0)
            Gauge.builder("pnl_attribution_greek_pnl", ref) { it.get() }
                .tag("book_id", book)
                .tag("greek", greek)
                .register(registry)
            ref
        }
}
