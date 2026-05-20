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

class EscalateAlertAcceptanceTest : FunSpec({

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

    fun triggeredAlert(id: String, severity: Severity = Severity.CRITICAL): AlertEvent = AlertEvent(
        id = id,
        ruleId = "r1",
        ruleName = "VaR Breach",
        type = AlertType.VAR_BREACH,
        severity = severity,
        message = "VaR exceeded threshold",
        currentValue = 150_000.0,
        threshold = 100_000.0,
        bookId = "book-1",
        triggeredAt = Instant.parse("2025-01-15T10:00:00Z"),
        status = AlertStatus.TRIGGERED,
    )

    test("a TRIGGERED alert exists — POST /alerts/{alertId}/escalate — returns 409 Conflict because escalation requires prior acknowledgement") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(triggeredAlert(id = "alert-esc-1"))

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-esc-1/escalate") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"VaR breach persisted, needs management visibility","assignee":"risk-manager"}""")
            }
            response.status shouldBe HttpStatusCode.Conflict
        }

        val persisted = eventRepo.findById("alert-esc-1")
        persisted?.status shouldBe AlertStatus.TRIGGERED
        persisted?.escalatedTo shouldBe null
        persisted?.escalatedAt shouldBe null
    }

    test("an ACKNOWLEDGED alert exists — POST /alerts/{alertId}/escalate with valid body — returns 200 with ESCALATED status and persists assignee from request") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(triggeredAlert(id = "alert-esc-1").copy(status = AlertStatus.ACKNOWLEDGED))

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-esc-1/escalate") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"VaR breach persisted, needs management visibility","assignee":"risk-manager"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]?.jsonPrimitive?.content shouldBe "ESCALATED"
            body["id"]?.jsonPrimitive?.content shouldBe "alert-esc-1"
            body["escalatedTo"]?.jsonPrimitive?.content shouldBe "risk-manager"
            body["escalatedAt"]?.jsonPrimitive?.content shouldNotBe null
        }

        val persisted = eventRepo.findById("alert-esc-1")
        persisted?.status shouldBe AlertStatus.ESCALATED
        persisted?.escalatedTo shouldBe "risk-manager"
        persisted?.escalatedAt shouldNotBe null
    }

    test("an ACKNOWLEDGED CRITICAL alert exists — POST /alerts/{alertId}/escalate without assignee — falls back to default assignee for severity") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(
            triggeredAlert(id = "alert-esc-default", severity = Severity.CRITICAL)
                .copy(status = AlertStatus.ACKNOWLEDGED),
        )

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-esc-default/escalate") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"market-wide stress event"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["escalatedTo"]?.jsonPrimitive?.content shouldBe "risk-manager,cro"
        }
    }

    test("an ACKNOWLEDGED alert exists — POST /alerts/{alertId}/escalate — transitions to ESCALATED") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(triggeredAlert(id = "alert-esc-ack").copy(status = AlertStatus.ACKNOWLEDGED))

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-esc-ack/escalate") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"position still risky after ack","assignee":"desk-head"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]?.jsonPrimitive?.content shouldBe "ESCALATED"
        }
    }

    test("a TRIGGERED alert exists — POST /alerts/{alertId}/escalate with blank reason — returns 400 Bad Request") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(triggeredAlert(id = "alert-esc-blank"))

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-esc-blank/escalate") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"   "}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }

        val persisted = eventRepo.findById("alert-esc-blank")
        persisted?.status shouldBe AlertStatus.TRIGGERED
    }

    test("nonexistent alert — POST /alerts/{alertId}/escalate — returns 404") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/does-not-exist/escalate") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"missing"}""")
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("a RESOLVED alert exists — POST /alerts/{alertId}/escalate — returns 409 Conflict") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(
            triggeredAlert(id = "alert-esc-resolved").copy(
                status = AlertStatus.RESOLVED,
                resolvedAt = Instant.parse("2025-01-15T10:30:00Z"),
                resolvedReason = "auto-cleared",
            ),
        )

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-esc-resolved/escalate") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"too late"}""")
            }
            response.status shouldBe HttpStatusCode.Conflict
        }
    }

    test("an ESCALATED alert exists — POST /alerts/{alertId}/escalate — returns 409 Conflict") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(
            triggeredAlert(id = "alert-esc-already").copy(
                status = AlertStatus.ESCALATED,
                escalatedAt = Instant.parse("2025-01-15T10:30:00Z"),
                escalatedTo = "risk-manager",
            ),
        )

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-esc-already/escalate") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"already-done"}""")
            }
            response.status shouldBe HttpStatusCode.Conflict
        }
    }
})
