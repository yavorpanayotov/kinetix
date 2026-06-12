package com.kinetix.notification.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.time.Instant

class AlertModelsTest : FunSpec({

    test("AlertType lists all alert categories including the kx-yndy execution-lifecycle types") {
        AlertType.entries.map { it.name } shouldContainExactly listOf(
            "VAR_BREACH", "PNL_THRESHOLD", "RISK_LIMIT",
            "DELTA_BREACH", "VEGA_BREACH", "CONCENTRATION",
            "MARGIN_BREACH", "MARGIN_CALL", "SETTLEMENT_FAILS",
            "DATA_STALE", "DATA_STALENESS", "LIQUIDITY_CONCENTRATION",
            "REGIME_CHANGE", "FACTOR_CONCENTRATION", "LIMIT_BREACH",
            "RISK_BUDGET_EXCEEDED",
        )
    }

    test("Severity has three levels") {
        Severity.entries.map { it.name } shouldContainExactly listOf(
            "INFO", "WARNING", "CRITICAL",
        )
    }

    test("AlertStatus has four values including ESCALATED") {
        AlertStatus.entries.map { it.name } shouldContainExactly listOf(
            "TRIGGERED", "ACKNOWLEDGED", "ESCALATED", "RESOLVED",
        )
    }

    test("DeliveryChannel has four options including PAGER_DUTY") {
        DeliveryChannel.entries.map { it.name } shouldContainExactly listOf(
            "IN_APP", "EMAIL", "WEBHOOK", "PAGER_DUTY",
        )
    }

    test("AlertRule has required fields") {
        val rule = AlertRule(
            id = "rule-1",
            name = "VaR Limit",
            type = AlertType.VAR_BREACH,
            threshold = 100_000.0,
            operator = ComparisonOperator.GREATER_THAN,
            severity = Severity.CRITICAL,
            channels = listOf(DeliveryChannel.IN_APP, DeliveryChannel.EMAIL),
            enabled = true,
        )
        rule.id shouldBe "rule-1"
        rule.name shouldBe "VaR Limit"
        rule.type shouldBe AlertType.VAR_BREACH
        rule.threshold shouldBe 100_000.0
        rule.operator shouldBe ComparisonOperator.GREATER_THAN
        rule.severity shouldBe Severity.CRITICAL
        rule.channels shouldContainExactly listOf(DeliveryChannel.IN_APP, DeliveryChannel.EMAIL)
        rule.enabled shouldBe true
    }

    test("AlertEvent has required fields") {
        val now = Instant.now()
        val event = AlertEvent(
            id = "evt-1",
            ruleId = "rule-1",
            ruleName = "VaR Limit",
            type = AlertType.VAR_BREACH,
            severity = Severity.CRITICAL,
            message = "VaR exceeded threshold",
            currentValue = 150_000.0,
            threshold = 100_000.0,
            bookId = "port-1",
            triggeredAt = now,
        )
        event.id shouldBe "evt-1"
        event.ruleId shouldBe "rule-1"
        event.ruleName shouldBe "VaR Limit"
        event.type shouldBe AlertType.VAR_BREACH
        event.severity shouldBe Severity.CRITICAL
        event.message.shouldNotBeBlank()
        event.currentValue shouldBe 150_000.0
        event.threshold shouldBe 100_000.0
        event.bookId shouldBe "port-1"
        event.triggeredAt shouldBe now
    }
})
