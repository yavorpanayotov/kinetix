package com.kinetix.notification.delivery

import io.micrometer.core.instrument.MeterRegistry

/**
 * Publishes in-app delivery metrics for `notification-service` — the owner of
 * the alert UI-push path — so the `overview/notification-service.json` Grafana
 * dashboard can be driven directly from the platform's Prometheus scrape.
 *
 * `notification-service` has no WebSocket server: the genuine UI-push path is
 * [InAppDeliveryService.deliver], which persists an alert event to Postgres so
 * the UI can poll it back. Two meters are emitted, both tagged by `severity`
 * (`INFO`/`WARNING`/`CRITICAL`):
 *
 *  - `notification_inapp_messages_delivered_total{severity}` — Counter, one
 *    increment per alert event successfully persisted for in-app delivery.
 *  - `notification_inapp_delivery_failures_total{severity}` — Counter, one
 *    increment each time an in-app delivery fails (the persist raised). A
 *    rising count means alerts are not reaching the UI.
 *
 * Both meters are registered lazily on first use against the supplied
 * [MeterRegistry], which in production is the application's
 * `PrometheusMeterRegistry`.
 */
class InAppDeliveryMetrics(
    private val registry: MeterRegistry,
) {
    /**
     * Records that one in-app alert message of [severity] was successfully
     * delivered (persisted for the UI to poll).
     */
    fun recordDelivered(severity: String) {
        registry.counter(MESSAGES_DELIVERED_TOTAL, "severity", severity).increment()
    }

    /**
     * Records that an in-app delivery of an alert message of [severity] failed.
     */
    fun recordFailure(severity: String) {
        registry.counter(DELIVERY_FAILURES_TOTAL, "severity", severity).increment()
    }

    companion object {
        const val MESSAGES_DELIVERED_TOTAL = "notification_inapp_messages_delivered_total"
        const val DELIVERY_FAILURES_TOTAL = "notification_inapp_delivery_failures_total"
    }
}
