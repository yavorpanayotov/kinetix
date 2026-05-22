package com.kinetix.notification.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Instrumentation contract for the in-app delivery metrics that drive the
 * `overview/notification-service.json` Grafana dashboard (checkbox 4.6 of
 * plans/grafana-v2.md):
 *
 *   - notification_inapp_messages_delivered_total{severity}  Counter
 *   - notification_inapp_delivery_failures_total{severity}   Counter
 *
 * Both metrics are asserted as Micrometer meters of the expected type and, where
 * the Prometheus wire name matters for the dashboard's PromQL, via a real
 * [PrometheusMeterRegistry] `.scrape()`.
 */
class InAppDeliveryMetricsTest : FunSpec({

    // ---------------------------------------------------------------------
    // notification_inapp_messages_delivered_total — Counter (severity)
    // ---------------------------------------------------------------------

    test("recordDelivered registers a counter tagged by severity") {
        val registry = SimpleMeterRegistry()
        InAppDeliveryMetrics(registry).recordDelivered("CRITICAL")

        val meter = registry.find("notification_inapp_messages_delivered_total")
            .tag("severity", "CRITICAL")
            .meter()
        meter shouldNotBe null
        (meter is Counter) shouldBe true
    }

    test("recordDelivered increments the matching severity counter") {
        val registry = SimpleMeterRegistry()
        val metrics = InAppDeliveryMetrics(registry)
        metrics.recordDelivered("CRITICAL")
        metrics.recordDelivered("CRITICAL")
        metrics.recordDelivered("WARNING")

        registry.counter("notification_inapp_messages_delivered_total", "severity", "CRITICAL")
            .count() shouldBe 2.0
        registry.counter("notification_inapp_messages_delivered_total", "severity", "WARNING")
            .count() shouldBe 1.0
    }

    // ---------------------------------------------------------------------
    // notification_inapp_delivery_failures_total — Counter (severity)
    // ---------------------------------------------------------------------

    test("recordFailure registers a counter tagged by severity") {
        val registry = SimpleMeterRegistry()
        InAppDeliveryMetrics(registry).recordFailure("CRITICAL")

        val meter = registry.find("notification_inapp_delivery_failures_total")
            .tag("severity", "CRITICAL")
            .meter()
        meter shouldNotBe null
        (meter is Counter) shouldBe true
    }

    test("recordFailure increments the matching severity counter") {
        val registry = SimpleMeterRegistry()
        val metrics = InAppDeliveryMetrics(registry)
        metrics.recordFailure("WARNING")
        metrics.recordFailure("WARNING")
        metrics.recordFailure("INFO")

        registry.counter("notification_inapp_delivery_failures_total", "severity", "WARNING")
            .count() shouldBe 2.0
        registry.counter("notification_inapp_delivery_failures_total", "severity", "INFO")
            .count() shouldBe 1.0
    }

    // ---------------------------------------------------------------------
    // Prometheus wire-format names — the dashboard PromQL must match exactly
    // ---------------------------------------------------------------------

    test("both in-app delivery metrics scrape under their expected Prometheus names") {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metrics = InAppDeliveryMetrics(registry)
        metrics.recordDelivered("CRITICAL")
        metrics.recordFailure("WARNING")

        val scrape = registry.scrape()
        scrape shouldContain "notification_inapp_messages_delivered_total{"
        scrape shouldContain "notification_inapp_delivery_failures_total{"
    }
})
