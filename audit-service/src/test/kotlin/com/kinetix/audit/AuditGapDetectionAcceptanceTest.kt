package com.kinetix.audit

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.module
import com.kinetix.audit.persistence.DatabaseTestSetup
import com.kinetix.audit.persistence.ExposedAuditEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

private val BASE_TIME: Instant = Instant.parse("2026-05-20T10:00:00Z")

/**
 * PR 11.1 — failure-mode coverage proving the audit trail is trustworthy under
 * partial failure. A dropped or never-published audit event leaves a hole in the
 * monotonic `sequence_number` column; `/api/v1/audit/gaps` must surface that hole
 * so operators know the chain is incomplete.
 *
 * This test inserts a real hash-chained run into a real Postgres (Testcontainers)
 * with a deliberate sequence-number gap and asserts the endpoint reports the exact
 * missing range — never an in-memory fake.
 */
class AuditGapDetectionAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedAuditEventRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE audit_events RESTART IDENTITY")
        }
    }

    fun event(tradeId: String, sequenceNumber: Long, receivedAt: Instant) = AuditEvent(
        tradeId = tradeId,
        bookId = "book-gap",
        eventType = "TRADE_BOOKED",
        receivedAt = receivedAt,
        sequenceNumber = sequenceNumber,
    )

    test("detects a sequence-number gap and reports the exact missing range") {
        // A run of audit events with a deliberate hole: 1, 2, 3 then 6, 7 —
        // sequence numbers 4 and 5 were never persisted (a dropped event).
        listOf(1L, 2L, 3L, 6L, 7L).forEachIndexed { i, seq ->
            repository.save(
                event(
                    tradeId = "t-$seq",
                    sequenceNumber = seq,
                    receivedAt = BASE_TIME.plusSeconds(i.toLong()),
                )
            )
        }

        testApplication {
            application { module(repository) }

            val response = client.get("/api/v1/audit/gaps")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["gapCount"]?.jsonPrimitive?.int shouldBe 1

            val gaps = body["gaps"]!!.jsonArray
            gaps.size shouldBe 1

            val gap = gaps[0].jsonObject
            // The gap is bounded by the last present sequence before the hole (3)
            // and the first present sequence after it (6); 4 and 5 are missing.
            gap["afterSequence"]?.jsonPrimitive?.long shouldBe 3L
            gap["beforeSequence"]?.jsonPrimitive?.long shouldBe 6L
            gap["missingCount"]?.jsonPrimitive?.long shouldBe 2L
        }
    }

    test("reports no gaps when the persisted sequence is contiguous") {
        listOf(1L, 2L, 3L, 4L, 5L).forEachIndexed { i, seq ->
            repository.save(
                event(
                    tradeId = "t-$seq",
                    sequenceNumber = seq,
                    receivedAt = BASE_TIME.plusSeconds(i.toLong()),
                )
            )
        }

        testApplication {
            application { module(repository) }

            val response = client.get("/api/v1/audit/gaps")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["gapCount"]?.jsonPrimitive?.int shouldBe 0
            body["gaps"]!!.jsonArray.size shouldBe 0
        }
    }
})
