package com.kinetix.risk.persistence

import com.kinetix.common.model.BookId
import com.kinetix.risk.routes.dtos.BatchStressRunResultResponse
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedLatestStressBatchRepository(
    private val db: Database? = null,
) : LatestStressBatchRepository {

    override suspend fun save(
        bookId: BookId,
        result: BatchStressRunResultResponse,
    ): Unit = newSuspendedTransaction(db = db) {
        LatestStressBatchesTable.upsert(LatestStressBatchesTable.bookId) {
            it[LatestStressBatchesTable.bookId] = bookId.value
            it[LatestStressBatchesTable.result] = result
            it[computedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    override suspend fun findLatestByBookId(
        bookId: BookId,
    ): BatchStressRunResultResponse? = newSuspendedTransaction(db = db) {
        LatestStressBatchesTable
            .selectAll()
            .where { LatestStressBatchesTable.bookId eq bookId.value }
            .singleOrNull()
            ?.get(LatestStressBatchesTable.result)
    }
}
