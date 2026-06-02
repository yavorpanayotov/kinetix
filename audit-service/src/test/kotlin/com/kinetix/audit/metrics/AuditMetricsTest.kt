package com.kinetix.audit.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration

/**
 * Instrumentation contract for the audit-trail metrics that drive the
 * `overview/audit-service.json` Grafana dashboard (checkbox 4.7 of
 * docs/plans/grafana-v2.md):
 *
 *   - audit_records_appended_total              Counter
 *   - audit_record_write_seconds                Timer  (percentile histogram)
 *   - audit_chain_verifications_total{outcome}  Counter (PASS / FAIL)
 *   - audit_chain_length                        Gauge
 *
 * Each metric is asserted as a Micrometer meter of the expected type and, where
 * the Prometheus wire name matters for the dashboard's PromQL, via a real
 * [PrometheusMeterRegistry] `.scrape()`.
 */
class AuditMetricsTest : FunSpec({

    // ---------------------------------------------------------------------
    // audit_records_appended_total — Counter
    // ---------------------------------------------------------------------

    test("recordAppend registers an append counter") {
        val registry = SimpleMeterRegistry()
        AuditMetrics(registry).recordAppend()

        val meter = registry.find("audit_records_appended_total").meter()
        meter shouldNotBe null
        (meter is Counter) shouldBe true
    }

    test("recordAppend increments the append counter once per call") {
        val registry = SimpleMeterRegistry()
        val metrics = AuditMetrics(registry)
        metrics.recordAppend()
        metrics.recordAppend()
        metrics.recordAppend()

        registry.counter("audit_records_appended_total").count() shouldBe 3.0
    }

    // ---------------------------------------------------------------------
    // audit_record_write_seconds — Timer
    // ---------------------------------------------------------------------

    test("recordWrite registers a write-latency timer") {
        val registry = SimpleMeterRegistry()
        AuditMetrics(registry).recordWrite(Duration.ofMillis(5))

        val meter = registry.find("audit_record_write_seconds").meter()
        meter shouldNotBe null
        (meter is Timer) shouldBe true
    }

    test("recordWrite observes one sample per call and accumulates total time") {
        val registry = SimpleMeterRegistry()
        val metrics = AuditMetrics(registry)
        metrics.recordWrite(Duration.ofMillis(10))
        metrics.recordWrite(Duration.ofMillis(30))

        val timer = registry.timer("audit_record_write_seconds")
        timer.count() shouldBe 2L
        timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe 40.0
    }

    // ---------------------------------------------------------------------
    // audit_chain_verifications_total{outcome} — Counter
    // ---------------------------------------------------------------------

    test("recordVerification registers an outcome-tagged verification counter") {
        val registry = SimpleMeterRegistry()
        AuditMetrics(registry).recordVerification(passed = true)

        val meter = registry.find("audit_chain_verifications_total")
            .tag("outcome", "PASS")
            .meter()
        meter shouldNotBe null
        (meter is Counter) shouldBe true
    }

    test("recordVerification increments the PASS and FAIL counters independently") {
        val registry = SimpleMeterRegistry()
        val metrics = AuditMetrics(registry)
        metrics.recordVerification(passed = true)
        metrics.recordVerification(passed = true)
        metrics.recordVerification(passed = false)

        registry.counter("audit_chain_verifications_total", "outcome", "PASS")
            .count() shouldBe 2.0
        registry.counter("audit_chain_verifications_total", "outcome", "FAIL")
            .count() shouldBe 1.0
    }

    // ---------------------------------------------------------------------
    // audit_chain_length — Gauge
    // ---------------------------------------------------------------------

    test("setChainLength registers a chain-length gauge") {
        val registry = SimpleMeterRegistry()
        AuditMetrics(registry).setChainLength(7L)

        val meter = registry.find("audit_chain_length").meter()
        meter shouldNotBe null
        (meter is Gauge) shouldBe true
    }

    test("setChainLength reports the latest chain length set") {
        val registry = SimpleMeterRegistry()
        val metrics = AuditMetrics(registry)
        metrics.setChainLength(3L)
        metrics.setChainLength(11L)

        registry.find("audit_chain_length").gauge()!!.value() shouldBe 11.0
    }

    // ---------------------------------------------------------------------
    // Prometheus wire-format names — the dashboard PromQL must match exactly
    // ---------------------------------------------------------------------

    test("all audit-trail metrics scrape under their expected Prometheus names") {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metrics = AuditMetrics(registry)
        metrics.recordAppend()
        metrics.recordWrite(Duration.ofMillis(12))
        metrics.recordVerification(passed = true)
        metrics.recordVerification(passed = false)
        metrics.setChainLength(42L)

        val scrape = registry.scrape()
        scrape shouldContain "audit_records_appended_total"
        scrape shouldContain "audit_record_write_seconds"
        scrape shouldContain "audit_chain_verifications_total{outcome=\"PASS\"}"
        scrape shouldContain "audit_chain_verifications_total{outcome=\"FAIL\"}"
        scrape shouldContain "audit_chain_length"
    }
})
