package com.kinetix.regulatory.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes model-governance metrics for `regulatory-service` — the owner of VaR
 * backtesting and regulatory submissions — so the `risk/regulatory.json` Grafana
 * dashboard can be driven directly from the platform's Prometheus scrape.
 *
 * Four meters are emitted:
 *
 *  - `regulatory_backtest_runs_total{book_id,test,outcome}` — Counter, one
 *    increment per backtest per statistical test. `test` is `KUPIEC` or
 *    `CHRISTOFFERSEN`; `outcome` is `PASS` or `FAIL`. The dashboard derives the
 *    pass/fail split with `sum by (outcome) (...)` and the pass rate from the
 *    ratio of `PASS` to total.
 *  - `regulatory_backtest_exceptions_total{book_id,zone}` — Counter, accumulates
 *    the number of VaR backtesting exceptions (days where the realised loss
 *    breached the predicted VaR). `zone` is the Basel traffic-light zone the
 *    backtest fell into (`GREEN`/`YELLOW`/`RED`), so the dashboard can show
 *    exception accrual and the rate at which they arrive.
 *  - `regulatory_backtest_current_exceptions{book_id}` — Gauge, the violation
 *    count of the most recent backtest for a book, for an at-a-glance "current
 *    exceptions per book" view. Named distinctly from the
 *    `regulatory_backtest_exceptions_total` counter so the two do not collide
 *    on the same Prometheus base metric family (`_total` is a counter suffix).
 *    Backed by an [AtomicReference] so a later backtest updates the reported
 *    value in place rather than re-registering the meter.
 *  - `regulatory_submission_outcomes_total{report_type,outcome}` — Counter, one
 *    increment each time a regulatory submission reaches a terminal outcome
 *    (`SUBMITTED` when filed with the regulator, `ACKNOWLEDGED` when the
 *    regulator confirms receipt).
 *
 * All meters are registered lazily on first use against the supplied
 * [MeterRegistry], which in production is the application's
 * `PrometheusMeterRegistry`.
 */
class RegulatoryGovernanceMetrics(
    private val registry: MeterRegistry,
) {
    private val exceptionGauges = ConcurrentHashMap<String, AtomicReference<Double>>()

    /**
     * Records the outcome of a single VaR backtest run. Increments the per-test
     * pass/fail run counters, accumulates the VaR backtesting exception count
     * into the zone-tagged counter, and updates the per-book current-exception
     * gauge to the latest violation count.
     */
    fun recordBacktest(
        bookId: String,
        violationCount: Int,
        kupiecPass: Boolean,
        christoffersenPass: Boolean,
        trafficLightZone: String,
    ) {
        recordRun(bookId, TEST_KUPIEC, kupiecPass)
        recordRun(bookId, TEST_CHRISTOFFERSEN, christoffersenPass)

        if (violationCount > 0) {
            registry.counter(
                BACKTEST_EXCEPTIONS_TOTAL,
                "book_id", bookId,
                "zone", trafficLightZone,
            ).increment(violationCount.toDouble())
        }

        exceptionGauges.computeIfAbsent(bookId) { registerExceptionGauge(it) }
            .set(violationCount.toDouble())
    }

    /**
     * Records that a regulatory submission for [reportType] reached a terminal
     * [outcome] — `SUBMITTED` or `ACKNOWLEDGED`.
     */
    fun recordSubmissionOutcome(reportType: String, outcome: String) {
        registry.counter(
            SUBMISSION_OUTCOMES_TOTAL,
            "report_type", reportType,
            "outcome", outcome,
        ).increment()
    }

    private fun recordRun(bookId: String, test: String, passed: Boolean) {
        registry.counter(
            BACKTEST_RUNS_TOTAL,
            "book_id", bookId,
            "test", test,
            "outcome", if (passed) OUTCOME_PASS else OUTCOME_FAIL,
        ).increment()
    }

    private fun registerExceptionGauge(bookId: String): AtomicReference<Double> {
        val ref = AtomicReference(0.0)
        Gauge.builder(BACKTEST_EXCEPTIONS) { ref.get() }
            .description("VaR backtesting exceptions in the latest backtest for a book (owner: regulatory-service)")
            .tag("book_id", bookId)
            .register(registry)
        return ref
    }

    companion object {
        const val BACKTEST_RUNS_TOTAL = "regulatory_backtest_runs_total"
        const val BACKTEST_EXCEPTIONS_TOTAL = "regulatory_backtest_exceptions_total"
        const val BACKTEST_EXCEPTIONS = "regulatory_backtest_current_exceptions"
        const val SUBMISSION_OUTCOMES_TOTAL = "regulatory_submission_outcomes_total"

        const val TEST_KUPIEC = "KUPIEC"
        const val TEST_CHRISTOFFERSEN = "CHRISTOFFERSEN"

        const val OUTCOME_PASS = "PASS"
        const val OUTCOME_FAIL = "FAIL"

        /** Terminal submission outcome — filed with the regulator. */
        const val OUTCOME_SUBMITTED = "SUBMITTED"

        /** Terminal submission outcome — receipt confirmed by the regulator. */
        const val OUTCOME_ACKNOWLEDGED = "ACKNOWLEDGED"
    }
}
