package com.kinetix.notification.seed

import com.kinetix.notification.engine.RulesEngine
import com.kinetix.notification.model.AlertStatus
import com.kinetix.notification.model.Severity
import com.kinetix.notification.persistence.AlertAcknowledgementsTable
import com.kinetix.notification.persistence.AlertEventsTable
import com.kinetix.notification.persistence.AlertRulesTable
import com.kinetix.notification.persistence.DatabaseTestSetup
import com.kinetix.notification.persistence.ExposedAlertEventRepository
import com.kinetix.notification.persistence.ExposedAlertRuleRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Acceptance test for PR 3 item 9 of [docs/plans/demo-follow-up.md] — when the
 * stress demo scenario is loaded the notification queue should already surface
 * pre-fired alerts for the three stress books so that the demo presenter can
 * open `AlertDrillDownPanel` on step 6 of [docs/demos/stress.md] without
 * waiting for live risk results.
 *
 * The seeded breach magnitudes come from
 * [com.kinetix.position.seed.StressScenario] (lines 263-273 in particular —
 * the `stress-vol` notional limit at $35M with intraday $40M).
 */
class StressAlertSeedingAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()

    beforeEach {
        transaction(db) {
            AlertAcknowledgementsTable.deleteAll()
            AlertEventsTable.deleteAll()
            AlertRulesTable.deleteAll()
        }
    }

    test("seeding the notification dev data — stress scenario books — surfaces at least two pre-fired alerts when the demo presenter opens AlertDrillDownPanel") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        val engine = RulesEngine(ruleRepo, eventRepository = eventRepo)

        DevDataSeeder(engine, eventRepo).seed()

        val all = eventRepo.findRecent(limit = 200)
        val stressBooks = setOf("stress-vol", "stress-momentum", "stress-credit")
        val stressAlerts = all.filter { it.bookId in stressBooks }

        // The plan asks for >= 2 alerts visible in AlertDrillDownPanel; we seed
        // four so the demo presenter has notional + concentration on stress-vol
        // plus advisory alerts on the two healthy books.
        stressAlerts.size shouldBeGreaterThanOrEqual 2
    }

    test("seeding the notification dev data — stress-vol breach magnitudes — match the limits defined in StressScenario.kt:263-273") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        val engine = RulesEngine(ruleRepo, eventRepository = eventRepo)

        DevDataSeeder(engine, eventRepo).seed()

        val volAlerts = eventRepo.findRecent(limit = 200).filter { it.bookId == "stress-vol" }
        // Two breaches per stress.md step 2: notional + concentration.
        volAlerts.size shouldBeGreaterThanOrEqual 2

        val notional = volAlerts.firstOrNull { it.message.contains("Notional", ignoreCase = true) }
            ?: error("expected a notional breach alert for stress-vol; got messages=${volAlerts.map { it.message }}")
        // StressScenario.kt:270 — `limitValue = 35000000` for stress-vol notional.
        notional.threshold shouldBe 35_000_000.0
        // Stress-vol gross runs ~$47M (see docs/demos/stress.md step 3); the
        // alert should be triggered above the limit.
        (notional.currentValue > notional.threshold) shouldBe true
        notional.severity shouldBe Severity.CRITICAL

        val concentration = volAlerts.firstOrNull { it.message.contains("Concentration", ignoreCase = true) }
            ?: error("expected a concentration breach alert for stress-vol; got messages=${volAlerts.map { it.message }}")
        concentration.severity shouldBe Severity.CRITICAL
    }

    test("seeding the notification dev data — stress books — every stress alert is TRIGGERED so AlertDrillDownPanel sees them as active") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        val engine = RulesEngine(ruleRepo, eventRepository = eventRepo)

        DevDataSeeder(engine, eventRepo).seed()

        val stressBooks = setOf("stress-vol", "stress-momentum", "stress-credit")
        val stressAlerts = eventRepo.findRecent(limit = 200).filter { it.bookId in stressBooks }

        stressAlerts.map { it.status }.toSet() shouldBe setOf(AlertStatus.TRIGGERED)
    }

    test("seeding the notification dev data — stress scenario — covers each stress book (vol, momentum, credit) at least once") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        val engine = RulesEngine(ruleRepo, eventRepository = eventRepo)

        DevDataSeeder(engine, eventRepo).seed()

        val booksWithAlerts = eventRepo.findRecent(limit = 200).map { it.bookId }.toSet()

        booksWithAlerts shouldContainAll listOf("stress-vol", "stress-momentum", "stress-credit")
    }

    test("seeding the notification dev data — stress-vol notional alert message — references the seeded gross above the 35M limit so the demo narrative reads cleanly") {
        val ruleRepo = ExposedAlertRuleRepository(db)
        val eventRepo = ExposedAlertEventRepository(db)
        val engine = RulesEngine(ruleRepo, eventRepository = eventRepo)

        DevDataSeeder(engine, eventRepo).seed()

        val volAlerts = eventRepo.findRecent(limit = 200).filter { it.bookId == "stress-vol" }
        val notional = volAlerts.first { it.message.contains("Notional", ignoreCase = true) }

        notional.message shouldContain "stress-vol"
        notional.message shouldContain "35,000,000"
    }
})
