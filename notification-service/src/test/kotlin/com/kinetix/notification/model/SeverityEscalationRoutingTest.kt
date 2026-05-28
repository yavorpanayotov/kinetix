package com.kinetix.notification.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Alert severities drive how a notification is escalated: a CRITICAL
 * portfolio breach pages the on-call risk officer and posts to a Slack
 * room; a LOW data-staleness warning only flips a status badge in the UI.
 * The escalation routing must be deterministic — every severity tier
 * resolves to exactly one ordered list of [DeliveryChannel]s, and every
 * tier above a given level escalates further than the tier below it.
 *
 * The existing service-level [Severity] (INFO/WARNING/CRITICAL) captures
 * the "what happened" axis; this new [AlertSeverity] (CRITICAL/HIGH/MEDIUM/
 * LOW) captures the "who needs to know, and how" axis. The two coexist
 * because their lifecycles are independent — Severity is the input from
 * the upstream risk engine, AlertSeverity is the operator-tier triage
 * decision used by the routing layer.
 */
class SeverityEscalationRoutingTest : FunSpec({

    test("AlertSeverity declares exactly four tiers, ordered CRITICAL > HIGH > MEDIUM > LOW") {
        AlertSeverity.entries shouldContainExactlyInAnyOrder
            listOf(AlertSeverity.CRITICAL, AlertSeverity.HIGH, AlertSeverity.MEDIUM, AlertSeverity.LOW)
        AlertSeverity.CRITICAL.tier shouldBe 4
        AlertSeverity.HIGH.tier shouldBe 3
        AlertSeverity.MEDIUM.tier shouldBe 2
        AlertSeverity.LOW.tier shouldBe 1
    }

    test("CRITICAL escalates to every channel — page, slack, email, in-app") {
        AlertSeverity.CRITICAL.escalationChannels() shouldBe listOf(
            DeliveryChannel.PAGER_DUTY,
            DeliveryChannel.WEBHOOK,
            DeliveryChannel.EMAIL,
            DeliveryChannel.IN_APP,
        )
    }

    test("HIGH escalates to webhook (Slack), email, and in-app but does NOT page") {
        val channels = AlertSeverity.HIGH.escalationChannels()
        channels shouldBe listOf(
            DeliveryChannel.WEBHOOK,
            DeliveryChannel.EMAIL,
            DeliveryChannel.IN_APP,
        )
        channels.contains(DeliveryChannel.PAGER_DUTY) shouldBe false
    }

    test("MEDIUM escalates to email and in-app only") {
        AlertSeverity.MEDIUM.escalationChannels() shouldBe listOf(
            DeliveryChannel.EMAIL,
            DeliveryChannel.IN_APP,
        )
    }

    test("LOW escalates to in-app only — no off-platform notification") {
        AlertSeverity.LOW.escalationChannels() shouldBe listOf(DeliveryChannel.IN_APP)
    }

    test("each tier escalates strictly more than the tier below it") {
        val tiers = AlertSeverity.entries.sortedBy { it.tier }
        for (i in 1 until tiers.size) {
            val lower = tiers[i - 1].escalationChannels().toSet()
            val upper = tiers[i].escalationChannels().toSet()
            // Strictly larger
            lower.size shouldNotBe upper.size
            upper.containsAll(lower) shouldBe true
        }
    }

    test("fromServiceSeverity maps CRITICAL -> CRITICAL, WARNING -> MEDIUM, INFO -> LOW") {
        AlertSeverity.fromServiceSeverity(Severity.CRITICAL) shouldBe AlertSeverity.CRITICAL
        AlertSeverity.fromServiceSeverity(Severity.WARNING) shouldBe AlertSeverity.MEDIUM
        AlertSeverity.fromServiceSeverity(Severity.INFO) shouldBe AlertSeverity.LOW
    }
})
