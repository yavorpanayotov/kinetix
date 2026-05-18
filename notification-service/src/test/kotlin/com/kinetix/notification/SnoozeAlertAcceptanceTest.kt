package com.kinetix.notification

import com.kinetix.notification.delivery.InAppDeliveryService
import com.kinetix.notification.engine.RulesEngine
import com.kinetix.notification.model.AlertEvent
import com.kinetix.notification.model.AlertStatus
import com.kinetix.notification.model.AlertType
import com.kinetix.notification.model.Severity
import com.kinetix.notification.persistence.AlertAcknowledgementsTable
import com.kinetix.notification.persistence.AlertEventsTable
import com.kinetix.notification.persistence.AlertRulesTable
import com.kinetix.notification.persistence.DatabaseTestSetup
import com.kinetix.notification.persistence.ExposedAlertAcknowledgementRepository
import com.kinetix.notification.persistence.ExposedAlertEventRepository
import com.kinetix.notification.persistence.ExposedAlertRuleRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.temporal.ChronoUnit

class SnoozeAlertAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()

    beforeEach {
        transaction(db) {
            AlertAcknowledgementsTable.deleteAll()
            AlertEventsTable.deleteAll()
            AlertRulesTable.deleteAll()
        }
    }

    fun newRepos(): Triple<ExposedAlertEventRepository, ExposedAlertAcknowledgementRepository, RulesEngine> {
        val eventRepo = ExposedAlertEventRepository(db)
        val ackRepo = ExposedAlertAcknowledgementRepository(db)
        val ruleRepo = ExposedAlertRuleRepository(db)
        val rulesEngine = RulesEngine(ruleRepo, eventRepository = eventRepo)
        return Triple(eventRepo, ackRepo, rulesEngine)
    }

    fun baseAlert(id: String, status: AlertStatus = AlertStatus.TRIGGERED): AlertEvent = AlertEvent(
        id = id,
        ruleId = "r1",
        ruleName = "VaR Breach",
        type = AlertType.VAR_BREACH,
        severity = Severity.CRITICAL,
        message = "VaR exceeded threshold",
        currentValue = 150_000.0,
        threshold = 100_000.0,
        bookId = "book-1",
        triggeredAt = Instant.parse("2025-01-15T10:00:00Z"),
        status = status,
    )

    test("a TRIGGERED alert exists — POST /alerts/{alertId}/snooze with a future timestamp — returns 200, persists snoozedUntil, keeps status TRIGGERED") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(baseAlert("alert-snooze-1"))
        val future = Instant.now().plus(1, ChronoUnit.HOURS)

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-snooze-1/snooze") {
                contentType(ContentType.Application.Json)
                setBody("""{"snoozedUntil":"$future"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["id"]?.jsonPrimitive?.content shouldBe "alert-snooze-1"
            body["status"]?.jsonPrimitive?.content shouldBe "TRIGGERED"
            body["snoozedUntil"]?.jsonPrimitive?.content shouldNotBe null
        }

        val persisted = eventRepo.findById("alert-snooze-1")
        persisted?.status shouldBe AlertStatus.TRIGGERED
        persisted?.snoozedUntil shouldNotBe null
    }

    test("an ACKNOWLEDGED alert can also be snoozed — status remains ACKNOWLEDGED") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(baseAlert("alert-snooze-ack", AlertStatus.ACKNOWLEDGED))
        val future = Instant.now().plus(30, ChronoUnit.MINUTES)

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-snooze-ack/snooze") {
                contentType(ContentType.Application.Json)
                setBody("""{"snoozedUntil":"$future"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]?.jsonPrimitive?.content shouldBe "ACKNOWLEDGED"
        }
    }

    test("an ESCALATED alert can also be snoozed — status remains ESCALATED") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(
            baseAlert("alert-snooze-esc").copy(
                status = AlertStatus.ESCALATED,
                escalatedAt = Instant.parse("2025-01-15T10:05:00Z"),
                escalatedTo = "risk-manager",
            ),
        )
        val future = Instant.now().plus(15, ChronoUnit.MINUTES)

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-snooze-esc/snooze") {
                contentType(ContentType.Application.Json)
                setBody("""{"snoozedUntil":"$future"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]?.jsonPrimitive?.content shouldBe "ESCALATED"
        }
    }

    test("a RESOLVED alert — POST /alerts/{alertId}/snooze — returns 409 Conflict") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(
            baseAlert("alert-snooze-resolved").copy(
                status = AlertStatus.RESOLVED,
                resolvedAt = Instant.parse("2025-01-15T10:30:00Z"),
                resolvedReason = "auto-cleared",
            ),
        )
        val future = Instant.now().plus(1, ChronoUnit.HOURS)

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-snooze-resolved/snooze") {
                contentType(ContentType.Application.Json)
                setBody("""{"snoozedUntil":"$future"}""")
            }
            response.status shouldBe HttpStatusCode.Conflict
        }

        val persisted = eventRepo.findById("alert-snooze-resolved")
        persisted?.snoozedUntil shouldBe null
    }

    test("POST /alerts/{alertId}/snooze with a past timestamp — returns 400 Bad Request") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(baseAlert("alert-snooze-past"))
        val past = Instant.now().minus(1, ChronoUnit.MINUTES)

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-snooze-past/snooze") {
                contentType(ContentType.Application.Json)
                setBody("""{"snoozedUntil":"$past"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }

        val persisted = eventRepo.findById("alert-snooze-past")
        persisted?.snoozedUntil shouldBe null
    }

    test("POST /alerts/{alertId}/snooze with an unparseable timestamp — returns 400 Bad Request") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(baseAlert("alert-snooze-junk"))

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-snooze-junk/snooze") {
                contentType(ContentType.Application.Json)
                setBody("""{"snoozedUntil":"not-a-timestamp"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST /alerts/{alertId}/snooze for an unknown alert id — returns 404 Not Found") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        val future = Instant.now().plus(1, ChronoUnit.HOURS)

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/does-not-exist/snooze") {
                contentType(ContentType.Application.Json)
                setBody("""{"snoozedUntil":"$future"}""")
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})
