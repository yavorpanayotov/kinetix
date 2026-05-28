package com.kinetix.notification.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Alert rules fire on telemetry that arrives in bursts — a single price
 * spike can trigger the same VaR-breach rule on the same trade three
 * times in eight seconds. Without suppression the trader's blotter
 * floods, the on-call pager pings repeatedly for what is one underlying
 * event, and the team starts ignoring legitimate alerts. The suppression
 * window pins down a deterministic rule: within five minutes of an
 * already-delivered alert, any subsequent firing of the *same* rule on
 * the *same* entity is silently dropped. The window is per-(rule,entity)
 * key — a different trade hitting the same rule is not suppressed, and
 * the same trade hitting a different rule is not suppressed.
 */
class DuplicateAlertSuppressionTest : FunSpec({

    val t0 = Instant.parse("2026-05-28T09:00:00Z")
    val ruleA = "var-breach"
    val ruleB = "margin-call"
    val entityX = "trade-42"
    val entityY = "trade-43"

    test("first alert for a (rule, entity) is never suppressed") {
        val window = DuplicateAlertSuppressionWindow(windowSeconds = 300)
        window.shouldDeliver(ruleA, entityX, t0) shouldBe true
    }

    test("second alert for the same (rule, entity) within the window is suppressed") {
        val window = DuplicateAlertSuppressionWindow(windowSeconds = 300)
        window.shouldDeliver(ruleA, entityX, t0)
        window.shouldDeliver(ruleA, entityX, t0.plusSeconds(60)) shouldBe false
    }

    test("alert at the boundary (exactly windowSeconds later) is suppressed") {
        val window = DuplicateAlertSuppressionWindow(windowSeconds = 300)
        window.shouldDeliver(ruleA, entityX, t0)
        window.shouldDeliver(ruleA, entityX, t0.plusSeconds(300)) shouldBe false
    }

    test("alert past the window (windowSeconds + 1) is delivered again") {
        val window = DuplicateAlertSuppressionWindow(windowSeconds = 300)
        window.shouldDeliver(ruleA, entityX, t0)
        window.shouldDeliver(ruleA, entityX, t0.plusSeconds(301)) shouldBe true
    }

    test("a different entity hitting the same rule is not suppressed") {
        val window = DuplicateAlertSuppressionWindow(windowSeconds = 300)
        window.shouldDeliver(ruleA, entityX, t0)
        window.shouldDeliver(ruleA, entityY, t0.plusSeconds(60)) shouldBe true
    }

    test("the same entity hitting a different rule is not suppressed") {
        val window = DuplicateAlertSuppressionWindow(windowSeconds = 300)
        window.shouldDeliver(ruleA, entityX, t0)
        window.shouldDeliver(ruleB, entityX, t0.plusSeconds(60)) shouldBe true
    }

    test("a re-delivered alert past the window does NOT reset the start of the next window prematurely") {
        val window = DuplicateAlertSuppressionWindow(windowSeconds = 300)
        window.shouldDeliver(ruleA, entityX, t0) shouldBe true
        window.shouldDeliver(ruleA, entityX, t0.plusSeconds(301)) shouldBe true
        // The second delivery is now the new anchor for the 5-min window.
        window.shouldDeliver(ruleA, entityX, t0.plusSeconds(400)) shouldBe false
    }
})
