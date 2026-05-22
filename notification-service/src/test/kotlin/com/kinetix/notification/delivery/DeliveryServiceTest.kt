package com.kinetix.notification.delivery

import com.kinetix.notification.model.*
import com.kinetix.notification.persistence.AlertEventRepository
import com.kinetix.notification.persistence.InMemoryAlertEventRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant

private fun sampleAlert(id: String = "evt-1") = AlertEvent(
    id = id,
    ruleId = "rule-1",
    ruleName = "VaR Limit",
    type = AlertType.VAR_BREACH,
    severity = Severity.CRITICAL,
    message = "VaR exceeded threshold",
    currentValue = 150_000.0,
    threshold = 100_000.0,
    bookId = "port-1",
    triggeredAt = Instant.parse("2025-01-15T10:00:00Z"),
)

class InAppDeliveryServiceTest : FunSpec({

    test("deliver stores alert") {
        val service = InAppDeliveryService(InMemoryAlertEventRepository())
        service.deliver(sampleAlert())
        service.getRecentAlerts() shouldHaveSize 1
        service.getRecentAlerts()[0].id shouldBe "evt-1"
    }

    test("recent alerts returns most recent first") {
        val service = InAppDeliveryService(InMemoryAlertEventRepository())
        service.deliver(sampleAlert("evt-1"))
        service.deliver(sampleAlert("evt-2"))
        service.deliver(sampleAlert("evt-3"))
        val alerts = service.getRecentAlerts()
        alerts[0].id shouldBe "evt-3"
        alerts[1].id shouldBe "evt-2"
        alerts[2].id shouldBe "evt-1"
    }

    test("recent alerts respects limit") {
        val service = InAppDeliveryService(InMemoryAlertEventRepository())
        repeat(10) { service.deliver(sampleAlert("evt-$it")) }
        val alerts = service.getRecentAlerts(limit = 3)
        alerts shouldHaveSize 3
    }

    test("deliver increments the in-app delivered counter on a successful persist") {
        val registry = SimpleMeterRegistry()
        val service = InAppDeliveryService(
            InMemoryAlertEventRepository(),
            InAppDeliveryMetrics(registry),
        )

        service.deliver(sampleAlert())

        registry.counter("notification_inapp_messages_delivered_total", "severity", "CRITICAL")
            .count() shouldBe 1.0
        registry.counter("notification_inapp_delivery_failures_total", "severity", "CRITICAL")
            .count() shouldBe 0.0
    }

    test("deliver tags the delivered counter with the alert severity") {
        val registry = SimpleMeterRegistry()
        val service = InAppDeliveryService(
            InMemoryAlertEventRepository(),
            InAppDeliveryMetrics(registry),
        )

        service.deliver(sampleAlert().copy(severity = Severity.WARNING))

        registry.counter("notification_inapp_messages_delivered_total", "severity", "WARNING")
            .count() shouldBe 1.0
    }

    test("deliver increments the failure counter and rethrows when the persist fails") {
        val registry = SimpleMeterRegistry()
        val service = InAppDeliveryService(
            FailingAlertEventRepository(),
            InAppDeliveryMetrics(registry),
        )

        shouldThrow<IllegalStateException> {
            service.deliver(sampleAlert())
        }

        registry.counter("notification_inapp_delivery_failures_total", "severity", "CRITICAL")
            .count() shouldBe 1.0
        registry.counter("notification_inapp_messages_delivered_total", "severity", "CRITICAL")
            .count() shouldBe 0.0
    }

    test("deliver works without a metrics collaborator") {
        val service = InAppDeliveryService(InMemoryAlertEventRepository())
        service.deliver(sampleAlert())
        service.getRecentAlerts() shouldHaveSize 1
    }
})

/**
 * An [AlertEventRepository] whose `save` always fails — used to exercise the
 * delivery-failure metric path of [InAppDeliveryService.deliver].
 */
private class FailingAlertEventRepository : AlertEventRepository {
    private val delegate = InMemoryAlertEventRepository()
    override suspend fun save(event: AlertEvent): Unit =
        throw IllegalStateException("persist failed")
    override suspend fun findRecent(limit: Int, status: AlertStatus?) =
        delegate.findRecent(limit, status)
    override suspend fun findActiveByRuleAndBook(ruleId: String, bookId: String) =
        delegate.findActiveByRuleAndBook(ruleId, bookId)
    override suspend fun findLatestByRuleAndBook(ruleId: String, bookId: String) =
        delegate.findLatestByRuleAndBook(ruleId, bookId)
    override suspend fun findActiveByBook(bookId: String) = delegate.findActiveByBook(bookId)
    override suspend fun updateStatus(
        id: String,
        status: AlertStatus,
        resolvedAt: Instant?,
        resolvedReason: String?,
    ) = delegate.updateStatus(id, status, resolvedAt, resolvedReason)
    override suspend fun acknowledge(id: String, acknowledgedAt: Instant) =
        delegate.acknowledge(id, acknowledgedAt)
    override suspend fun escalate(
        id: String,
        escalatedAt: Instant,
        escalatedTo: String,
        promotedSeverity: Severity?,
    ) = delegate.escalate(id, escalatedAt, escalatedTo, promotedSeverity)
    override suspend fun findAcknowledgedBefore(cutoff: Instant) =
        delegate.findAcknowledgedBefore(cutoff)
    override suspend fun findById(id: String) = delegate.findById(id)
    override suspend fun snooze(id: String, until: Instant) = delegate.snooze(id, until)
}

class EmailDeliveryServiceTest : FunSpec({

    test("deliver records email") {
        val service = EmailDeliveryService()
        service.deliver(sampleAlert())
        service.sentEmails shouldHaveSize 1
        service.sentEmails[0].id shouldBe "evt-1"
    }
})

class WebhookDeliveryServiceTest : FunSpec({

    test("deliver records webhook") {
        val service = WebhookDeliveryService()
        service.deliver(sampleAlert())
        service.sentWebhooks shouldHaveSize 1
        service.sentWebhooks[0].id shouldBe "evt-1"
    }
})

class DeliveryRouterTest : FunSpec({

    test("routes to correct channels") {
        val inApp = InAppDeliveryService(InMemoryAlertEventRepository())
        val email = EmailDeliveryService()
        val webhook = WebhookDeliveryService()
        val router = DeliveryRouter(listOf(inApp, email, webhook))

        router.route(sampleAlert(), listOf(DeliveryChannel.IN_APP))

        inApp.getRecentAlerts() shouldHaveSize 1
        email.sentEmails.shouldBeEmpty()
        webhook.sentWebhooks.shouldBeEmpty()
    }

    test("routes to multiple channels") {
        val inApp = InAppDeliveryService(InMemoryAlertEventRepository())
        val email = EmailDeliveryService()
        val webhook = WebhookDeliveryService()
        val router = DeliveryRouter(listOf(inApp, email, webhook))

        router.route(sampleAlert(), listOf(DeliveryChannel.IN_APP, DeliveryChannel.EMAIL))

        inApp.getRecentAlerts() shouldHaveSize 1
        email.sentEmails shouldHaveSize 1
        webhook.sentWebhooks.shouldBeEmpty()
    }

    test("skips channels not configured") {
        val inApp = InAppDeliveryService(InMemoryAlertEventRepository())
        val router = DeliveryRouter(listOf(inApp))

        router.route(sampleAlert(), listOf(DeliveryChannel.IN_APP, DeliveryChannel.WEBHOOK))

        inApp.getRecentAlerts() shouldHaveSize 1
    }
})
