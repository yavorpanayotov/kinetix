package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of a single item in `GET /api/v1/risk/jobs/{bookId}` exposed by
 * `risk-orchestrator` (see `JobHistoryRoutes.kt` and
 * `routes/dtos/ValuationJobSummaryResponse.kt`).
 *
 * Only the fields the demo orchestrator needs to drive EOD promotion are
 * modelled here. The Ktor JSON decoder is configured with
 * `ignoreUnknownKeys=true`, so additional upstream fields are silently
 * tolerated.
 */
@Serializable
data class ValuationJobSummary(
    val jobId: String,
    val bookId: String,
    val status: String,
    val valuationDate: String? = null,
    val runLabel: String? = null,
    val promotedAt: String? = null,
)
