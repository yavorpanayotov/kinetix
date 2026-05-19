package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of the body sent to `PATCH /api/v1/risk/jobs/{jobId}/label`
 * exposed by `risk-orchestrator` (see `EodPromotionRoutes.kt` and
 * `routes/dtos/PromoteEodRequest.kt`).
 *
 * For EOD promotion [label] is the string name of `RunLabel.OFFICIAL_EOD`
 * and [promotedBy] is the demo orchestrator's identifier.
 */
@Serializable
data class PromoteEodRequest(
    val label: String,
    val promotedBy: String,
)
