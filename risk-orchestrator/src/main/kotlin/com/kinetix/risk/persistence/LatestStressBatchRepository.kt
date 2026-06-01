package com.kinetix.risk.persistence

import com.kinetix.common.model.BookId
import com.kinetix.risk.routes.dtos.BatchStressRunResultResponse

/**
 * Persists the most recent batch stress-test result per book so the Scenarios
 * tab can populate on cold open without a fresh "Run All Scenarios" click
 * (issue kx-kjse).
 *
 * Mirrors the canned-stress persist-and-fetch shape: [save] overwrites the
 * single stored result for the book, [findLatestByBookId] reads it back.
 */
interface LatestStressBatchRepository {
    suspend fun save(bookId: BookId, result: BatchStressRunResultResponse)
    suspend fun findLatestByBookId(bookId: BookId): BatchStressRunResultResponse?
}
