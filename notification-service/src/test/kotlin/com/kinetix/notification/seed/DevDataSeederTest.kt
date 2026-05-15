package com.kinetix.notification.seed

import com.kinetix.notification.engine.RulesEngine
import com.kinetix.notification.model.AlertType
import com.kinetix.notification.model.Severity
import com.kinetix.notification.persistence.InMemoryAlertEventRepository
import com.kinetix.notification.persistence.InMemoryAlertRuleRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class DevDataSeederTest : FunSpec({

    test("seeds 7 rules and 29 alert events when empty") {
        val engine = RulesEngine(InMemoryAlertRuleRepository())
        val eventRepo = InMemoryAlertEventRepository()
        val seeder = DevDataSeeder(engine, eventRepo)

        seeder.seed()

        engine.listRules() shouldHaveSize 7
        // 25 baseline events + 4 stress-scenario pre-fired alerts
        // (stress-vol notional + concentration, stress-momentum, stress-credit)
        // — see DevDataSeeder.kt for the demo-follow-up PR 3 §9 block.
        eventRepo.findRecent(50) shouldHaveSize 29
    }

    test("seeds limit breach rule with CRITICAL severity") {
        val engine = RulesEngine(InMemoryAlertRuleRepository())
        val eventRepo = InMemoryAlertEventRepository()
        DevDataSeeder(engine, eventRepo).seed()

        val limitBreachRule = engine.listRules().find { it.id == "seed-rule-limit-breach" }!!
        limitBreachRule.type shouldBe AlertType.LIMIT_BREACH
        limitBreachRule.severity shouldBe Severity.CRITICAL
    }

    test("skips seeding when rules already exist") {
        val engine = RulesEngine(InMemoryAlertRuleRepository())
        val eventRepo = InMemoryAlertEventRepository()
        val seeder = DevDataSeeder(engine, eventRepo)

        seeder.seed()
        val rulesAfterFirstSeed = engine.listRules().size

        seeder.seed()

        engine.listRules() shouldHaveSize rulesAfterFirstSeed
    }

    test("seeds VaR breach rule with CRITICAL severity") {
        val engine = RulesEngine(InMemoryAlertRuleRepository())
        val eventRepo = InMemoryAlertEventRepository()
        DevDataSeeder(engine, eventRepo).seed()

        val varRule = engine.listRules().find { it.id == "seed-rule-var-breach" }!!
        varRule.threshold shouldBe 15_000_000.0
        varRule.severity shouldBe Severity.CRITICAL
    }

    test("seeds PnL threshold rule with WARNING severity") {
        val engine = RulesEngine(InMemoryAlertRuleRepository())
        val eventRepo = InMemoryAlertEventRepository()
        DevDataSeeder(engine, eventRepo).seed()

        val pnlRule = engine.listRules().find { it.id == "seed-rule-pnl-threshold" }!!
        pnlRule.threshold shouldBe 5_000_000.0
        pnlRule.severity shouldBe Severity.WARNING
    }

    test("seeds risk limit rule with INFO severity") {
        val engine = RulesEngine(InMemoryAlertRuleRepository())
        val eventRepo = InMemoryAlertEventRepository()
        DevDataSeeder(engine, eventRepo).seed()

        val riskRule = engine.listRules().find { it.id == "seed-rule-risk-limit" }!!
        riskRule.threshold shouldBe 25_000_000.0
        riskRule.severity shouldBe Severity.INFO
    }
})
