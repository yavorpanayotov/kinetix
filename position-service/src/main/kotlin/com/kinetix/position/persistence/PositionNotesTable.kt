package com.kinetix.position.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object PositionNotesTable : Table("position_notes") {
    val id = uuid("id")
    val bookId = text("book_id")
    val instrumentId = text("instrument_id")
    val note = text("note")
    val author = text("author")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
