package com.kinetix.fix.session

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedFixSessionStateRepository(private val db: Database? = null) : FixSessionStateRepository {

    override suspend fun findByVenue(venue: String): FixSessionState? = newSuspendedTransaction(db = db) {
        FixSessionStateTable
            .selectAll()
            .where { FixSessionStateTable.venue eq venue.uppercase() }
            .singleOrNull()
            ?.toState()
    }

    override suspend fun upsert(state: FixSessionState): Unit = newSuspendedTransaction(db = db) {
        val existing = FixSessionStateTable
            .selectAll()
            .where { FixSessionStateTable.venue eq state.venue }
            .count()
        if (existing == 0L) {
            FixSessionStateTable.insert { row ->
                row[venue] = state.venue
                row[senderSeqNum] = state.senderSeqNum
                row[targetSeqNum] = state.targetSeqNum
                row[lastLogonAt] = state.lastLogonAt?.atOffset(ZoneOffset.UTC)
                row[lastLogoutAt] = state.lastLogoutAt?.atOffset(ZoneOffset.UTC)
                row[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        } else {
            FixSessionStateTable.update({ FixSessionStateTable.venue eq state.venue }) { row ->
                row[senderSeqNum] = state.senderSeqNum
                row[targetSeqNum] = state.targetSeqNum
                row[lastLogonAt] = state.lastLogonAt?.atOffset(ZoneOffset.UTC)
                row[lastLogoutAt] = state.lastLogoutAt?.atOffset(ZoneOffset.UTC)
                row[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    override suspend fun all(): List<FixSessionState> = newSuspendedTransaction(db = db) {
        FixSessionStateTable.selectAll().map { it.toState() }
    }

    private fun ResultRow.toState(): FixSessionState = FixSessionState(
        venue = this[FixSessionStateTable.venue],
        senderSeqNum = this[FixSessionStateTable.senderSeqNum],
        targetSeqNum = this[FixSessionStateTable.targetSeqNum],
        lastLogonAt = this[FixSessionStateTable.lastLogonAt]?.toInstant(),
        lastLogoutAt = this[FixSessionStateTable.lastLogoutAt]?.toInstant(),
    )
}
