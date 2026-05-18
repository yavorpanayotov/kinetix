package com.kinetix.position.model

import java.time.Instant
import java.util.UUID

data class PositionNote(
    val id: UUID,
    val bookId: String,
    val instrumentId: String,
    val note: String,
    val author: String,
    val createdAt: Instant,
)
