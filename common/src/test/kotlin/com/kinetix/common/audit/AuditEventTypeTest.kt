package com.kinetix.common.audit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainAll

class AuditEventTypeTest : FunSpec({

    test("contains all required audit event types") {
        val names = AuditEventType.entries.map { it.name }
        names shouldContainAll listOf(
            "TRADE_BOOKED",
            "TRADE_AMENDED",
            "TRADE_CANCELLED",
            "RISK_CALCULATION_COMPLETED",
            "RISK_CALCULATION_FAILED",
            "STRESS_TEST_RUN",
            "MODEL_STATUS_CHANGED",
            "LIMIT_BREACHED",
            "LIMIT_INCREASE_APPROVED",
            "SCENARIO_APPROVED",
            "SCENARIO_RETIRED",
            "EOD_PROMOTED",
            "SUBMISSION_APPROVED",
            "RBAC_ACCESS_DENIED",
            "REPORT_GENERATED",
        )
    }

    test("valueOf resolves each type by name") {
        AuditEventType.valueOf("TRADE_BOOKED") shouldBe AuditEventType.TRADE_BOOKED
        AuditEventType.valueOf("RISK_CALCULATION_COMPLETED") shouldBe AuditEventType.RISK_CALCULATION_COMPLETED
        AuditEventType.valueOf("RISK_CALCULATION_FAILED") shouldBe AuditEventType.RISK_CALCULATION_FAILED
        AuditEventType.valueOf("MODEL_STATUS_CHANGED") shouldBe AuditEventType.MODEL_STATUS_CHANGED
        AuditEventType.valueOf("RBAC_ACCESS_DENIED") shouldBe AuditEventType.RBAC_ACCESS_DENIED
    }
})
