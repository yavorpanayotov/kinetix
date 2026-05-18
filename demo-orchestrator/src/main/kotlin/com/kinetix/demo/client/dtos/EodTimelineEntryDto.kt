package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of a single entry in `GET /api/v1/risk/eod-timeline/{bookId}`
 * exposed by `risk-orchestrator` (see `EodTimelineRoutes.kt`).
 *
 * Only the fields the demo orchestrator needs to pair VaR predictions with
 * realised P&L are modelled here. The Ktor JSON decoder is configured with
 * `ignoreUnknownKeys=true`, so additional upstream fields are silently
 * tolerated.
 */
@Serializable
data class EodTimelineEntryDto(
    val valuationDate: String,
    val varValue: Double? = null,
    val pvValue: Double? = null,
)
