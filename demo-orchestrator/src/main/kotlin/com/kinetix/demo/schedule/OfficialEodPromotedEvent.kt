package com.kinetix.demo.schedule

import kotlinx.serialization.Serializable

/**
 * Mirror of the `risk.official-eod` wire payload produced by
 * `risk-orchestrator`'s `KafkaOfficialEodPublisher`. The demo orchestrator
 * deserialises this shape locally to avoid a module-level dependency on
 * `risk-orchestrator`.
 *
 * Field names match the producer exactly so the JSON round-trips. The fields
 * the demo flow uses today are [bookId] (for the regulatory backtest endpoint)
 * and [valuationDate] (for the T+1 17:00 UTC submission deadline). The other
 * fields are kept so unknown-key deserialisation remains permissive even if
 * the producer adds more in the future.
 *
 * `valuationDate` is an ISO date string (e.g. `"2026-05-18"`).
 */
@Serializable
data class OfficialEodPromotedEvent(
    val jobId: String,
    val bookId: String,
    val valuationDate: String,
    val promotedBy: String,
    val promotedAt: String,
    val varValue: Double? = null,
    val expectedShortfall: Double? = null,
)
