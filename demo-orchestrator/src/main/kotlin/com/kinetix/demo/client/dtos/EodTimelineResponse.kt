package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /api/v1/risk/eod-timeline/{bookId}` exposed by
 * `risk-orchestrator`.
 *
 * Entries are returned ascending by `valuationDate`. The demo orchestrator
 * pairs each consecutive entry to compute a `(prediction, realised pnl)` sample
 * for regulatory backtesting.
 */
@Serializable
data class EodTimelineResponse(
    val bookId: String,
    val from: String,
    val to: String,
    val entries: List<EodTimelineEntryDto>,
)
