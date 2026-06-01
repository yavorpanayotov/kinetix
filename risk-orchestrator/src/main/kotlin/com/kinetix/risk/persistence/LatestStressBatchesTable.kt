package com.kinetix.risk.persistence

import com.kinetix.risk.routes.dtos.BatchStressRunResultResponse
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

/**
 * Stores the latest batch stress-test result per book (issue kx-kjse).
 *
 * One row per book — each batch sweep overwrites the previous result via an
 * UPSERT on the [bookId] primary key. The full
 * [BatchStressRunResultResponse] payload is persisted as a JSONB blob so the
 * GET endpoint can return exactly what the POST returned, letting the
 * Scenarios tab render the comparison grid on cold open.
 *
 * Mirrors the canned-stress persist-and-fetch shape (kx-wxy); the canned tile
 * caches a single three-field payload whereas a batch is a ranked list.
 */
object LatestStressBatchesTable : Table("latest_stress_batches") {
    val bookId = varchar("book_id", 64)
    val result = jsonb<BatchStressRunResultResponse>("result", Json)
    val computedAt = timestampWithTimeZone("computed_at")

    override val primaryKey = PrimaryKey(bookId)
}
