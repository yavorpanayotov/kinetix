package com.kinetix.position.fix

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset

/**
 * Persists cancel attempts to `cancel_attempts` (V23). The contract on
 * [CancelAttemptRecorder] is fire-and-forget — DB failures must NOT block the
 * sweeper's state-side EXPIRED transition, so insert errors are caught and
 * logged.
 */
class ExposedCancelAttemptRecorder(private val db: Database? = null) : CancelAttemptRecorder {

    private val logger = LoggerFactory.getLogger(ExposedCancelAttemptRecorder::class.java)

    override fun record(
        orderId: String,
        venue: String,
        status: CancelAttemptStatus,
        attemptedAt: Instant,
        detail: String,
    ) {
        try {
            runBlocking {
                newSuspendedTransaction(db = db) {
                    CancelAttemptsTable.insert {
                        it[CancelAttemptsTable.orderId] = orderId
                        it[CancelAttemptsTable.venue] = venue
                        it[CancelAttemptsTable.status] = status.name
                        it[CancelAttemptsTable.detail] = detail
                        it[CancelAttemptsTable.attemptedAt] = attemptedAt.atOffset(ZoneOffset.UTC)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "Failed to persist cancel_attempt orderId={} venue={} status={}: {}",
                orderId, venue, status, e.message,
            )
        }
    }
}
