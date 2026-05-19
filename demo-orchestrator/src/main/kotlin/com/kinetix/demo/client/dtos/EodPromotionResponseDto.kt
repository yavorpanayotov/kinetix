package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of the response to `PATCH /api/v1/risk/jobs/{jobId}/label`
 * exposed by `risk-orchestrator` (see `EodPromotionRoutes.kt` and
 * `routes/dtos/EodPromotionResponse.kt`).
 *
 * The Ktor JSON decoder is configured with `ignoreUnknownKeys=true`, so
 * additional upstream fields are silently tolerated.
 */
@Serializable
data class EodPromotionResponseDto(
    val jobId: String,
    val bookId: String,
    val valuationDate: String,
    val runLabel: String,
    val promotedAt: String? = null,
    val promotedBy: String? = null,
)
