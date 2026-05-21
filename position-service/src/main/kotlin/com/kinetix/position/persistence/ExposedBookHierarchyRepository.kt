package com.kinetix.position.persistence

import com.kinetix.position.model.BookHierarchyMapping
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedBookHierarchyRepository(private val db: Database? = null) : BookHierarchyRepository {

    override suspend fun findByBookId(bookId: String): BookHierarchyMapping? =
        newSuspendedTransaction(db = db) {
            BookHierarchyTable
                .selectAll()
                .where { BookHierarchyTable.bookId eq bookId }
                .singleOrNull()
                ?.let { it.toMapping() }
        }

    override suspend fun findByDeskId(deskId: String): List<BookHierarchyMapping> =
        newSuspendedTransaction(db = db) {
            BookHierarchyTable
                .selectAll()
                .where { BookHierarchyTable.deskId eq deskId }
                .map { it.toMapping() }
        }

    override suspend fun findAll(): List<BookHierarchyMapping> =
        newSuspendedTransaction(db = db) {
            BookHierarchyTable
                .selectAll()
                .map { it.toMapping() }
        }

    override suspend fun save(mapping: BookHierarchyMapping): Unit =
        newSuspendedTransaction(db = db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            BookHierarchyTable.upsert(BookHierarchyTable.bookId) {
                it[bookId] = mapping.bookId
                it[deskId] = mapping.deskId
                it[bookName] = mapping.bookName
                it[bookType] = mapping.bookType
                it[baseCurrency] = mapping.baseCurrency
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

    override suspend fun delete(bookId: String): Unit =
        newSuspendedTransaction(db = db) {
            BookHierarchyTable.deleteWhere { BookHierarchyTable.bookId eq bookId }
        }

    private fun ResultRow.toMapping(): BookHierarchyMapping = BookHierarchyMapping(
        bookId = this[BookHierarchyTable.bookId],
        deskId = this[BookHierarchyTable.deskId],
        bookName = this[BookHierarchyTable.bookName],
        bookType = this[BookHierarchyTable.bookType],
        baseCurrency = this[BookHierarchyTable.baseCurrency],
    )
}
