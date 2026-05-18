package com.kinetix.common.dtos

import kotlinx.serialization.Serializable

/**
 * Wire representation of a free-text note attached to a (book, instrument) pair.
 *
 * Defined in `common` because the UI overhaul (plan §7.3) needs the position-service
 * (producer) and the gateway (proxy) to share the exact same serialized shape;
 * keeping the DTO inside position-service would force the gateway to either depend
 * on the service module or duplicate the type.
 */
@Serializable
data class PositionNoteDto(
    val id: String,
    val bookId: String,
    val instrumentId: String,
    val note: String,
    val author: String,
    val createdAt: String, // ISO-8601 timestamp
)
