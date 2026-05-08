package com.kinetix.fix.session

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedFixMessageLogRepository(private val db: Database? = null) : FixMessageLogRepository {

    override suspend fun insert(entry: FixMessageLogEntry): Unit = newSuspendedTransaction(db = db) {
        FixMessageLogTable.insert { row ->
            row[venue] = entry.venue
            row[direction] = entry.direction
            row[msgType] = entry.msgType
            row[rawMessage] = entry.rawMessage
            row[clOrdId] = entry.clOrdId
            row[venueOrderId] = entry.venueOrderId
            row[orderStatus] = entry.orderStatus
            row[sentAt] = entry.sentAt.atOffset(ZoneOffset.UTC)
        }
    }

    override suspend fun markTerminal(venue: String, clOrdId: String): Unit = newSuspendedTransaction(db = db) {
        FixMessageLogTable.update(
            where = {
                FixMessageLogTable.venue eq venue.uppercase() and
                    (FixMessageLogTable.clOrdId eq clOrdId) and
                    (FixMessageLogTable.orderStatus eq "OPEN")
            }
        ) { row ->
            row[orderStatus] = "TERMINAL"
        }
    }

    override suspend fun findOpenClOrdIds(venue: String, withinHours: Int): List<String> =
        newSuspendedTransaction(db = db) {
            val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusHours(withinHours.toLong())
            FixMessageLogTable
                .selectAll()
                .where {
                    FixMessageLogTable.venue eq venue.uppercase() and
                        (FixMessageLogTable.direction eq "OUT") and
                        (FixMessageLogTable.msgType eq "D") and
                        (FixMessageLogTable.orderStatus eq "OPEN") and
                        (FixMessageLogTable.sentAt greaterEq cutoff)
                }
                .mapNotNull { it[FixMessageLogTable.clOrdId] }
                .distinct()
        }
}
