package com.kinetix.audit.routes

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.module
import com.kinetix.audit.persistence.DatabaseTestSetup
import com.kinetix.audit.persistence.ExposedAuditEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

/**
 * PR 4.7 — proves the audit-chain verification endpoint records its outcome
 * into the `audit_chain_verifications_total{outcome}` Prometheus counter so the
 * `overview/audit-service.json` Grafana dashboard can chart verification
 * pass/fail. Exercises the real Ktor route against a real Postgres instance
 * (Testcontainers) and scrapes the `/metrics` endpoint to assert the wire name.
 */
class AuditVerificationMetricsAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedAuditEventRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE audit_events RESTART IDENTITY")
        }
    }

    fun event(tradeId: String) = AuditEvent(
        tradeId = tradeId,
        eventType = "TRADE_BOOKED",
        receivedAt = Instant.parse("2026-05-21T10:00:00Z"),
    )

    test("GET /api/v1/audit/verify records a PASS verification outcome") {
        repository.save(event("t-1"))
        repository.save(event("t-2"))

        testApplication {
            application { module(repository) }

            client.get("/api/v1/audit/verify")
            val scrape = client.get("/metrics").bodyAsText()

            scrape shouldContain "audit_chain_verifications_total{outcome=\"PASS\"}"
        }
    }
})
