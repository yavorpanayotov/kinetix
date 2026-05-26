package com.kinetix.risk.cache

import com.kinetix.risk.routes.dtos.CannedStressResultResponse

/**
 * In-memory store of the most recent canned stress scenario result per book.
 * Populated by `POST /api/v1/risk/stress/{bookId}/canned/{scenarioName}` and
 * read by `GET /api/v1/risk/stress/{bookId}/canned`.
 *
 * Keyed on the book identifier alone — the demo currently fires a single
 * canned scenario per book so we always overwrite. If the canned-scenario
 * library grows beyond one entry we can switch to a `(bookId, scenario)`
 * compound key without changing the routes.
 */
interface CannedStressCache {
    fun put(bookId: String, result: CannedStressResultResponse)
    fun get(bookId: String): CannedStressResultResponse?
}
