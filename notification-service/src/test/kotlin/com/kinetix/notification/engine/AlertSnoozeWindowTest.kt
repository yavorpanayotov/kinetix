package com.kinetix.notification.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Traders snooze noisy alert rules for known maintenance windows: an
 * overnight rates curve will go stale during the daily 22:00 ET rebuild,
 * and the trader knows it. Without a snooze window, the staleness alert
 * pages the desk at 22:01, 22:02, 22:03 — every minute the rebuild runs
 * — for what is by design a no-op. The contract: a rule snoozed at t0
 * for N minutes delivers nothing until t0+N+1s and resumes normally
 * after that. Snoozes are per-rule-per-trader so one trader's snooze
 * does not silence another.
 */
class AlertSnoozeWindowTest : FunSpec({

    val t0 = Instant.parse("2026-05-28T22:00:00Z")
    val ruleA = "curve-staleness"
    val ruleB = "var-breach"
    val traderX = "trader-42"
    val traderY = "trader-43"

    test("an unsnoozed rule delivers normally") {
        val snooze = AlertSnoozeWindow()
        snooze.isSnoozed(ruleA, traderX, t0) shouldBe false
    }

    test("a rule snoozed for 5m suppresses delivery during the window") {
        val snooze = AlertSnoozeWindow()
        snooze.snooze(ruleA, traderX, t0, durationSeconds = 300)
        snooze.isSnoozed(ruleA, traderX, t0.plusSeconds(60)) shouldBe true
        snooze.isSnoozed(ruleA, traderX, t0.plusSeconds(299)) shouldBe true
    }

    test("a rule snoozed for 5m resumes delivery 1s after the window") {
        val snooze = AlertSnoozeWindow()
        snooze.snooze(ruleA, traderX, t0, durationSeconds = 300)
        snooze.isSnoozed(ruleA, traderX, t0.plusSeconds(301)) shouldBe false
    }

    test("snooze is per-(rule, trader): a different trader is unaffected") {
        val snooze = AlertSnoozeWindow()
        snooze.snooze(ruleA, traderX, t0, durationSeconds = 300)
        snooze.isSnoozed(ruleA, traderY, t0.plusSeconds(60)) shouldBe false
    }

    test("snooze is per-(rule, trader): a different rule for the same trader is unaffected") {
        val snooze = AlertSnoozeWindow()
        snooze.snooze(ruleA, traderX, t0, durationSeconds = 300)
        snooze.isSnoozed(ruleB, traderX, t0.plusSeconds(60)) shouldBe false
    }

    test("re-snoozing the same (rule, trader) extends the window from the new anchor") {
        val snooze = AlertSnoozeWindow()
        snooze.snooze(ruleA, traderX, t0, durationSeconds = 60)
        snooze.snooze(ruleA, traderX, t0.plusSeconds(30), durationSeconds = 300)
        // Original window ends at t0+60, but new window ends at t0+30+300 = t0+330.
        snooze.isSnoozed(ruleA, traderX, t0.plusSeconds(120)) shouldBe true
    }

    test("clear() removes the snooze immediately") {
        val snooze = AlertSnoozeWindow()
        snooze.snooze(ruleA, traderX, t0, durationSeconds = 300)
        snooze.clear(ruleA, traderX)
        snooze.isSnoozed(ruleA, traderX, t0.plusSeconds(60)) shouldBe false
    }
})
