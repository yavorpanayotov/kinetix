package com.kinetix.regulatory.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Instrumentation contract for the model-governance metrics that drive the
 * `risk/regulatory.json` Grafana dashboard (checkbox 4.4 of plans/grafana-v2.md):
 *
 *   - regulatory_backtest_runs_total{book_id,test,outcome}      Counter
 *   - regulatory_backtest_exceptions_total{book_id,zone}        Counter
 *   - regulatory_backtest_current_exceptions{book_id}           Gauge
 *   - regulatory_submission_outcomes_total{report_type,outcome} Counter
 *
 * Each metric is asserted both as a Micrometer meter of the expected type and,
 * where the Prometheus wire name matters for the dashboard's PromQL, via a real
 * [PrometheusMeterRegistry] `.scrape()`.
 */
class RegulatoryGovernanceMetricsTest : FunSpec({

    // ---------------------------------------------------------------------
    // regulatory_backtest_runs_total — Counter (book_id, test, outcome)
    // ---------------------------------------------------------------------

    test("recordBacktest registers a PASS run counter for the Kupiec test") {
        val registry = SimpleMeterRegistry()
        RegulatoryGovernanceMetrics(registry).recordBacktest(
            bookId = "book-1",
            violationCount = 3,
            kupiecPass = true,
            christoffersenPass = true,
            trafficLightZone = "GREEN",
        )

        val meter = registry.find("regulatory_backtest_runs_total")
            .tag("book_id", "book-1")
            .tag("test", "KUPIEC")
            .tag("outcome", "PASS")
            .meter()
        meter shouldNotBe null
        (meter is Counter) shouldBe true
    }

    test("a passing backtest increments the PASS run counter for both tests") {
        val registry = SimpleMeterRegistry()
        RegulatoryGovernanceMetrics(registry).recordBacktest(
            bookId = "book-1",
            violationCount = 2,
            kupiecPass = true,
            christoffersenPass = true,
            trafficLightZone = "GREEN",
        )

        registry.counter(
            "regulatory_backtest_runs_total",
            "book_id", "book-1", "test", "KUPIEC", "outcome", "PASS",
        ).count() shouldBe 1.0
        registry.counter(
            "regulatory_backtest_runs_total",
            "book_id", "book-1", "test", "CHRISTOFFERSEN", "outcome", "PASS",
        ).count() shouldBe 1.0
    }

    test("a failing backtest increments the FAIL run counter for the failing test") {
        val registry = SimpleMeterRegistry()
        RegulatoryGovernanceMetrics(registry).recordBacktest(
            bookId = "book-1",
            violationCount = 12,
            kupiecPass = false,
            christoffersenPass = true,
            trafficLightZone = "RED",
        )

        registry.counter(
            "regulatory_backtest_runs_total",
            "book_id", "book-1", "test", "KUPIEC", "outcome", "FAIL",
        ).count() shouldBe 1.0
        registry.counter(
            "regulatory_backtest_runs_total",
            "book_id", "book-1", "test", "CHRISTOFFERSEN", "outcome", "PASS",
        ).count() shouldBe 1.0
    }

    // ---------------------------------------------------------------------
    // regulatory_backtest_exceptions_total — Counter (book_id, zone)
    // ---------------------------------------------------------------------

    test("regulatory_backtest_exceptions_total accumulates the VaR violation count") {
        val registry = SimpleMeterRegistry()
        val metrics = RegulatoryGovernanceMetrics(registry)
        metrics.recordBacktest("book-1", violationCount = 3, kupiecPass = true, christoffersenPass = true, trafficLightZone = "GREEN")
        metrics.recordBacktest("book-1", violationCount = 5, kupiecPass = true, christoffersenPass = true, trafficLightZone = "YELLOW")

        registry.counter("regulatory_backtest_exceptions_total", "book_id", "book-1", "zone", "GREEN")
            .count() shouldBe 3.0
        registry.counter("regulatory_backtest_exceptions_total", "book_id", "book-1", "zone", "YELLOW")
            .count() shouldBe 5.0
    }

    test("a clean backtest with zero violations does not move the exceptions counter") {
        val registry = SimpleMeterRegistry()
        RegulatoryGovernanceMetrics(registry).recordBacktest(
            bookId = "book-1",
            violationCount = 0,
            kupiecPass = true,
            christoffersenPass = true,
            trafficLightZone = "GREEN",
        )

        registry.counter("regulatory_backtest_exceptions_total", "book_id", "book-1", "zone", "GREEN")
            .count() shouldBe 0.0
    }

    // ---------------------------------------------------------------------
    // regulatory_backtest_current_exceptions — Gauge (book_id)
    // ---------------------------------------------------------------------

    test("regulatory_backtest_current_exceptions gauge reflects the latest violation count per book") {
        val registry = SimpleMeterRegistry()
        val metrics = RegulatoryGovernanceMetrics(registry)
        metrics.recordBacktest("book-1", violationCount = 3, kupiecPass = true, christoffersenPass = true, trafficLightZone = "GREEN")

        val gauge = registry.find("regulatory_backtest_current_exceptions").tag("book_id", "book-1").gauge()
        gauge shouldNotBe null
        gauge!!.value() shouldBe 3.0
    }

    test("regulatory_backtest_current_exceptions gauge is updated by a subsequent backtest of the same book") {
        val registry = SimpleMeterRegistry()
        val metrics = RegulatoryGovernanceMetrics(registry)
        metrics.recordBacktest("book-1", violationCount = 3, kupiecPass = true, christoffersenPass = true, trafficLightZone = "GREEN")
        metrics.recordBacktest("book-1", violationCount = 8, kupiecPass = false, christoffersenPass = true, trafficLightZone = "YELLOW")

        registry.find("regulatory_backtest_current_exceptions").tag("book_id", "book-1").gauge()!!
            .value() shouldBe 8.0
    }

    test("regulatory_backtest_current_exceptions gauge is per book") {
        val registry = SimpleMeterRegistry()
        val metrics = RegulatoryGovernanceMetrics(registry)
        metrics.recordBacktest("book-1", violationCount = 2, kupiecPass = true, christoffersenPass = true, trafficLightZone = "GREEN")
        metrics.recordBacktest("book-2", violationCount = 9, kupiecPass = false, christoffersenPass = false, trafficLightZone = "RED")

        registry.find("regulatory_backtest_current_exceptions").tag("book_id", "book-1").gauge()!!.value() shouldBe 2.0
        registry.find("regulatory_backtest_current_exceptions").tag("book_id", "book-2").gauge()!!.value() shouldBe 9.0
    }

    test("regulatory_backtest_current_exceptions is a Gauge") {
        val registry = SimpleMeterRegistry()
        RegulatoryGovernanceMetrics(registry).recordBacktest(
            bookId = "book-1",
            violationCount = 1,
            kupiecPass = true,
            christoffersenPass = true,
            trafficLightZone = "GREEN",
        )

        val meter = registry.find("regulatory_backtest_current_exceptions").tag("book_id", "book-1").meter()
        (meter is Gauge) shouldBe true
    }

    // ---------------------------------------------------------------------
    // regulatory_submission_outcomes_total — Counter (report_type, outcome)
    // ---------------------------------------------------------------------

    test("recordSubmissionOutcome registers a counter tagged by report_type and outcome") {
        val registry = SimpleMeterRegistry()
        RegulatoryGovernanceMetrics(registry).recordSubmissionOutcome("FRTB_SBM", "SUBMITTED")

        val meter = registry.find("regulatory_submission_outcomes_total")
            .tag("report_type", "FRTB_SBM")
            .tag("outcome", "SUBMITTED")
            .meter()
        meter shouldNotBe null
        (meter is Counter) shouldBe true
    }

    test("recordSubmissionOutcome increments the matching outcome counter") {
        val registry = SimpleMeterRegistry()
        val metrics = RegulatoryGovernanceMetrics(registry)
        metrics.recordSubmissionOutcome("FRTB_SBM", "SUBMITTED")
        metrics.recordSubmissionOutcome("FRTB_SBM", "SUBMITTED")
        metrics.recordSubmissionOutcome("FRTB_DRC", "ACKNOWLEDGED")

        registry.counter("regulatory_submission_outcomes_total", "report_type", "FRTB_SBM", "outcome", "SUBMITTED")
            .count() shouldBe 2.0
        registry.counter("regulatory_submission_outcomes_total", "report_type", "FRTB_DRC", "outcome", "ACKNOWLEDGED")
            .count() shouldBe 1.0
    }

    // ---------------------------------------------------------------------
    // Prometheus wire-format names — the dashboard PromQL must match exactly
    // ---------------------------------------------------------------------

    test("all governance metrics scrape under their expected Prometheus names") {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metrics = RegulatoryGovernanceMetrics(registry)
        metrics.recordBacktest("book-1", violationCount = 4, kupiecPass = true, christoffersenPass = false, trafficLightZone = "GREEN")
        metrics.recordSubmissionOutcome("FRTB_SBM", "SUBMITTED")

        val scrape = registry.scrape()
        scrape shouldContain "regulatory_backtest_runs_total{"
        scrape shouldContain "regulatory_backtest_exceptions_total{"
        scrape shouldContain "regulatory_backtest_current_exceptions{"
        scrape shouldContain "regulatory_submission_outcomes_total{"
    }
})
