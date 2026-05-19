package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /api/v1/risk/jobs/{bookId}` exposed by
 * `risk-orchestrator` (see `JobHistoryRoutes.kt` and
 * `routes/dtos/PaginatedJobsResponse.kt`).
 *
 * The demo orchestrator only needs the most-recent item to drive EOD
 * promotion, so [items] is the only field consumed today; [totalCount] is
 * deserialised for completeness.
 */
@Serializable
data class PaginatedJobsResponseDto(
    val items: List<ValuationJobSummary>,
    val totalCount: Long = 0,
)
