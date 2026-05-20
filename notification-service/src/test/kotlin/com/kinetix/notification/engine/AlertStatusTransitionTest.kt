package com.kinetix.notification.engine

import com.kinetix.notification.model.AlertStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AlertStatusTransitionTest : FunSpec({

    test("TRIGGERED transitions to ACKNOWLEDGED") {
        AlertStatus.TRIGGERED.canTransitionTo(AlertStatus.ACKNOWLEDGED) shouldBe true
    }

    test("TRIGGERED transitions to RESOLVED") {
        AlertStatus.TRIGGERED.canTransitionTo(AlertStatus.RESOLVED) shouldBe true
    }

    test("TRIGGERED cannot transition directly to ESCALATED — escalation requires prior acknowledgement") {
        AlertStatus.TRIGGERED.canTransitionTo(AlertStatus.ESCALATED) shouldBe false
    }

    test("ACKNOWLEDGED transitions to ESCALATED") {
        AlertStatus.ACKNOWLEDGED.canTransitionTo(AlertStatus.ESCALATED) shouldBe true
    }

    test("ACKNOWLEDGED transitions to RESOLVED") {
        AlertStatus.ACKNOWLEDGED.canTransitionTo(AlertStatus.RESOLVED) shouldBe true
    }

    test("ACKNOWLEDGED cannot transition back to TRIGGERED") {
        AlertStatus.ACKNOWLEDGED.canTransitionTo(AlertStatus.TRIGGERED) shouldBe false
    }

    test("ESCALATED transitions to RESOLVED") {
        AlertStatus.ESCALATED.canTransitionTo(AlertStatus.RESOLVED) shouldBe true
    }

    test("ESCALATED cannot transition back to TRIGGERED") {
        AlertStatus.ESCALATED.canTransitionTo(AlertStatus.TRIGGERED) shouldBe false
    }

    test("ESCALATED cannot transition to ACKNOWLEDGED") {
        AlertStatus.ESCALATED.canTransitionTo(AlertStatus.ACKNOWLEDGED) shouldBe false
    }

    test("RESOLVED is terminal and cannot transition to any status") {
        AlertStatus.RESOLVED.canTransitionTo(AlertStatus.TRIGGERED) shouldBe false
        AlertStatus.RESOLVED.canTransitionTo(AlertStatus.ACKNOWLEDGED) shouldBe false
        AlertStatus.RESOLVED.canTransitionTo(AlertStatus.ESCALATED) shouldBe false
        AlertStatus.RESOLVED.canTransitionTo(AlertStatus.RESOLVED) shouldBe false
    }
})
