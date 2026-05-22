package com.kinetix.audit.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes audit-trail metrics for `audit-service` — the owner of the
 * hash-chained audit log — so the `overview/audit-service.json` Grafana
 * dashboard can be driven directly from the platform's Prometheus scrape.
 *
 * Four meters are emitted:
 *
 *  - `audit_records_appended_total` — Counter, one increment per audit record
 *    successfully appended to the chain. The dashboard derives the append rate
 *    with `rate(...)`.
 *  - `audit_record_write_seconds` — Timer (percentile histogram), one sample
 *    per audit-record persist. The dashboard charts p50/p95/p99 write latency
 *    via `histogram_quantile(...)` over the `_bucket` series.
 *  - `audit_chain_verifications_total{outcome}` — Counter, one increment per
 *    chain-verification run. `outcome` is `PASS` when the chain verified intact
 *    or `FAIL` when tampering/corruption was detected, so the dashboard can
 *    show the pass/fail split and alert on any `FAIL`.
 *  - `audit_chain_length` — Gauge, the number of records currently in the
 *    audit chain. Backed by an [AtomicLong] so each append updates the reported
 *    value in place rather than re-registering the meter.
 *
 * All meters are registered eagerly against the supplied [MeterRegistry],
 * which in production is the application's `PrometheusMeterRegistry`.
 */
class AuditMetrics(
    private val registry: MeterRegistry,
) {
    private val appendCounter: Counter = Counter.builder(RECORDS_APPENDED_TOTAL)
        .description("Audit records appended to the hash chain (owner: audit-service)")
        .register(registry)

    private val writeTimer: Timer = Timer.builder(RECORD_WRITE_SECONDS)
        .description("Latency of persisting a single audit record (owner: audit-service)")
        .publishPercentileHistogram()
        .register(registry)

    private val chainLength = AtomicLong(0L)

    init {
        Gauge.builder(CHAIN_LENGTH) { chainLength.get().toDouble() }
            .description("Number of records currently in the audit hash chain (owner: audit-service)")
            .register(registry)
    }

    /** Records that one audit record was successfully appended to the chain. */
    fun recordAppend() {
        appendCounter.increment()
    }

    /** Records the latency of a single audit-record persist. */
    fun recordWrite(duration: Duration) {
        writeTimer.record(duration)
    }

    /**
     * Records the outcome of a single audit-chain verification run —
     * `PASS` when the chain verified intact, `FAIL` when tampering or
     * corruption was detected.
     */
    fun recordVerification(passed: Boolean) {
        registry.counter(
            CHAIN_VERIFICATIONS_TOTAL,
            "outcome", if (passed) OUTCOME_PASS else OUTCOME_FAIL,
        ).increment()
    }

    /** Sets the current audit-chain length reported by the `audit_chain_length` gauge. */
    fun setChainLength(length: Long) {
        chainLength.set(length)
    }

    companion object {
        const val RECORDS_APPENDED_TOTAL = "audit_records_appended_total"
        const val RECORD_WRITE_SECONDS = "audit_record_write_seconds"
        const val CHAIN_VERIFICATIONS_TOTAL = "audit_chain_verifications_total"
        const val CHAIN_LENGTH = "audit_chain_length"

        const val OUTCOME_PASS = "PASS"
        const val OUTCOME_FAIL = "FAIL"
    }
}
