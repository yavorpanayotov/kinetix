package com.kinetix.position.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object BookHierarchyTable : Table("book_hierarchy") {
    val bookId = varchar("book_id", 64)
    val deskId = varchar("desk_id", 64)
    val bookName = varchar("book_name", 255).nullable()
    val bookType = varchar("book_type", 64).nullable()
    val baseCurrency = varchar("base_currency", 3)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(bookId)
}
