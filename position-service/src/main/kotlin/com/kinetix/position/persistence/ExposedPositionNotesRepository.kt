package com.kinetix.position.persistence

import com.kinetix.position.model.PositionNote
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ExposedPositionNotesRepository(
    private val db: Database? = null,
) : PositionNotesRepository {

    override suspend fun list(bookId: String, instrumentId: String?): List<PositionNote> =
        newSuspendedTransaction(db = db) {
            PositionNotesTable
                .selectAll()
                .where {
                    if (instrumentId == null) {
                        PositionNotesTable.bookId eq bookId
                    } else {
                        (PositionNotesTable.bookId eq bookId) and
                            (PositionNotesTable.instrumentId eq instrumentId)
                    }
                }
                .orderBy(PositionNotesTable.createdAt, SortOrder.DESC)
                .map { it.toPositionNote() }
        }

    override suspend fun create(
        bookId: String,
        instrumentId: String,
        note: String,
        author: String,
    ): PositionNote = newSuspendedTransaction(db = db) {
        val newId = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        PositionNotesTable.insert {
            it[PositionNotesTable.id] = newId
            it[PositionNotesTable.bookId] = bookId
            it[PositionNotesTable.instrumentId] = instrumentId
            it[PositionNotesTable.note] = note
            it[PositionNotesTable.author] = author
            it[PositionNotesTable.createdAt] = now
        }
        PositionNote(
            id = newId,
            bookId = bookId,
            instrumentId = instrumentId,
            note = note,
            author = author,
            createdAt = now.toInstant(),
        )
    }

    override suspend fun deleteById(id: UUID): Boolean = newSuspendedTransaction(db = db) {
        PositionNotesTable.deleteWhere { PositionNotesTable.id eq id } > 0
    }

    private fun ResultRow.toPositionNote() = PositionNote(
        id = this[PositionNotesTable.id],
        bookId = this[PositionNotesTable.bookId],
        instrumentId = this[PositionNotesTable.instrumentId],
        note = this[PositionNotesTable.note],
        author = this[PositionNotesTable.author],
        createdAt = this[PositionNotesTable.createdAt].toInstant(),
    )
}
