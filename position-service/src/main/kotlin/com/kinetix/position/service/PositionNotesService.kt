package com.kinetix.position.service

import com.kinetix.position.model.PositionNote
import com.kinetix.position.persistence.PositionNotesRepository
import java.util.UUID

class PositionNotesService(
    private val repository: PositionNotesRepository,
) {

    suspend fun list(bookId: String, instrumentId: String? = null): List<PositionNote> =
        repository.list(bookId, instrumentId)

    suspend fun create(
        bookId: String,
        instrumentId: String,
        note: String,
        author: String,
    ): PositionNote {
        require(bookId.isNotBlank()) { "bookId must not be blank" }
        require(instrumentId.isNotBlank()) { "instrumentId must not be blank" }
        require(note.isNotBlank()) { "note must not be blank" }
        require(author.isNotBlank()) { "author must not be blank" }
        return repository.create(bookId, instrumentId, note, author)
    }

    suspend fun delete(id: UUID): Boolean = repository.deleteById(id)
}
