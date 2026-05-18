package com.kinetix.position.persistence

import com.kinetix.position.model.PositionNote
import java.util.UUID

interface PositionNotesRepository {
    /**
     * Returns notes for the given book, ordered newest-first.
     * When [instrumentId] is supplied, narrows the result to a single (book, instrument) pair.
     */
    suspend fun list(bookId: String, instrumentId: String? = null): List<PositionNote>

    /** Inserts a new note and returns the persisted row (with generated id and timestamp). */
    suspend fun create(bookId: String, instrumentId: String, note: String, author: String): PositionNote

    /** Deletes the note with the given id. Returns true if a row was removed, false otherwise. */
    suspend fun deleteById(id: UUID): Boolean
}
