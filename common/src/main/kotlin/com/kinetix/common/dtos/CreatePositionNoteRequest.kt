package com.kinetix.common.dtos

import kotlinx.serialization.Serializable

/**
 * Request payload for creating a new position note.
 *
 * `bookId` is intentionally not part of the body — it is taken from the URL path
 * (`/api/v1/books/{bookId}/notes`) so a single request cannot smuggle a note onto
 * a different book than the one the route is mounted under. `author` is derived
 * server-side from the authenticated principal.
 */
@Serializable
data class CreatePositionNoteRequest(
    val instrumentId: String,
    val note: String,
)
