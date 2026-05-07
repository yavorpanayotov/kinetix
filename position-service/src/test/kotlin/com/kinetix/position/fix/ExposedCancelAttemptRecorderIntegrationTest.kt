package com.kinetix.position.fix

import com.kinetix.position.persistence.DatabaseTestSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class ExposedCancelAttemptRecorderIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val recorder = ExposedCancelAttemptRecorder(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE cancel_attempts RESTART IDENTITY")
        }
    }

    test("records an ACCEPTED attempt with detail") {
        recorder.record(
            orderId = "ord-1", venue = "NYSE",
            status = CancelAttemptStatus.ACCEPTED,
            attemptedAt = Instant.parse("2026-05-04T20:00:00Z"),
            detail = "",
        )

        val rows = newSuspendedTransaction(db = db) {
            CancelAttemptsTable.selectAll().toList()
        }
        rows shouldHaveSize 1
        rows[0][CancelAttemptsTable.orderId] shouldBe "ord-1"
        rows[0][CancelAttemptsTable.venue] shouldBe "NYSE"
        rows[0][CancelAttemptsTable.status] shouldBe "ACCEPTED"
    }

    test("records each CancelAttemptStatus value") {
        val statuses = listOf(
            CancelAttemptStatus.ACCEPTED,
            CancelAttemptStatus.SESSION_DOWN,
            CancelAttemptStatus.UNKNOWN_VENUE,
            CancelAttemptStatus.INVALID_REQUEST,
            CancelAttemptStatus.RPC_FAILED,
        )
        statuses.forEach { status ->
            recorder.record(
                orderId = "ord-${status.name}", venue = "NYSE", status = status,
                attemptedAt = Instant.parse("2026-05-04T20:00:00Z"),
                detail = "",
            )
        }
        val persistedStatuses = newSuspendedTransaction(db = db) {
            CancelAttemptsTable.selectAll().map { it[CancelAttemptsTable.status] }.toSet()
        }
        persistedStatuses shouldBe statuses.map { it.name }.toSet()
    }

    test("multiple attempts on the same order are all retained (append-only)") {
        val now = Instant.parse("2026-05-04T20:00:00Z")
        recorder.record("ord-7", "NYSE", CancelAttemptStatus.SESSION_DOWN, now, "first")
        recorder.record("ord-7", "NYSE", CancelAttemptStatus.SESSION_DOWN, now.plusSeconds(60), "retry-1")
        recorder.record("ord-7", "NYSE", CancelAttemptStatus.ACCEPTED, now.plusSeconds(120), "retry-2")
        val rows = newSuspendedTransaction(db = db) {
            CancelAttemptsTable.selectAll().toList()
        }
        rows shouldHaveSize 3
    }
})
