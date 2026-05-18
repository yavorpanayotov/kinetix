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

class ResolveAlertAcceptanceTest : FunSpec({

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

    test("a TRIGGERED alert exists — POST /alerts/{alertId}/resolve with valid body — returns 200 with RESOLVED status and persists resolutionText in resolved_reason") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(baseAlert("alert-res-1"))

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-res-1/resolve") {
                contentType(ContentType.Application.Json)
                setBody("""{"resolutionText":"Position trimmed below limit"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]?.jsonPrimitive?.content shouldBe "RESOLVED"
            body["id"]?.jsonPrimitive?.content shouldBe "alert-res-1"
            body["resolvedAt"]?.jsonPrimitive?.content shouldNotBe null
            body["resolvedReason"]?.jsonPrimitive?.content shouldBe "Position trimmed below limit"
        }

        val persisted = eventRepo.findById("alert-res-1")
        persisted?.status shouldBe AlertStatus.RESOLVED
        persisted?.resolvedReason shouldBe "Position trimmed below limit"
        persisted?.resolvedAt shouldNotBe null
    }

    test("an ACKNOWLEDGED alert exists — POST /alerts/{alertId}/resolve — transitions to RESOLVED") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(baseAlert("alert-res-ack", AlertStatus.ACKNOWLEDGED))

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-res-ack/resolve") {
                contentType(ContentType.Application.Json)
                setBody("""{"resolutionText":"hedge applied"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]?.jsonPrimitive?.content shouldBe "RESOLVED"
        }
    }

    test("an ESCALATED alert exists — POST /alerts/{alertId}/resolve — transitions to RESOLVED") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(baseAlert("alert-res-esc", AlertStatus.ESCALATED))

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-res-esc/resolve") {
                contentType(ContentType.Application.Json)
                setBody("""{"resolutionText":"managed by CRO"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]?.jsonPrimitive?.content shouldBe "RESOLVED"
        }
    }

    test("a TRIGGERED alert exists — POST /alerts/{alertId}/resolve with blank resolutionText — returns 400 Bad Request") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(baseAlert("alert-res-blank"))

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-res-blank/resolve") {
                contentType(ContentType.Application.Json)
                setBody("""{"resolutionText":""}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }

        val persisted = eventRepo.findById("alert-res-blank")
        persisted?.status shouldBe AlertStatus.TRIGGERED
    }

    test("nonexistent alert — POST /alerts/{alertId}/resolve — returns 404") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/does-not-exist/resolve") {
                contentType(ContentType.Application.Json)
                setBody("""{"resolutionText":"any"}""")
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("an already RESOLVED alert exists — POST /alerts/{alertId}/resolve — returns 409 Conflict") {
        val (eventRepo, ackRepo, rulesEngine) = newRepos()
        eventRepo.save(
            baseAlert("alert-res-done").copy(
                status = AlertStatus.RESOLVED,
                resolvedAt = Instant.parse("2025-01-15T10:30:00Z"),
                resolvedReason = "earlier",
            ),
        )

        testApplication {
            application {
                module(rulesEngine, InAppDeliveryService(eventRepo), ackRepo)
            }
            val response = client.post("/api/v1/notifications/alerts/alert-res-done/resolve") {
                contentType(ContentType.Application.Json)
                setBody("""{"resolutionText":"again"}""")
            }
            response.status shouldBe HttpStatusCode.Conflict
        }
    }
})
