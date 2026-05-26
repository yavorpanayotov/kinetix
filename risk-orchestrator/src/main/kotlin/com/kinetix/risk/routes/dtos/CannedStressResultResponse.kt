package com.kinetix.risk.routes.dtos

import kotlinx.serialization.Serializable

/**
 * Per-book canned stress scenario result surfaced on the Risk overview tile
 * (issue kx-wxy). The shape is deliberately minimal — scenario name, delta-PV,
 * and the as-of timestamp — so the UI tile can render without pulling the
 * full [StressTestResponse] payload.
 *
 * Produced by `POST /api/v1/risk/stress/{bookId}/canned/{scenarioName}` and
 * surfaced again by `GET /api/v1/risk/stress/{bookId}/canned` for the most
 * recent canned result cached in-memory.
 */
@Serializable
data class CannedStressResultResponse(
    val bookId: String,
    val scenario: String,
    val deltaPv: String,
    val asOf: String,
)
