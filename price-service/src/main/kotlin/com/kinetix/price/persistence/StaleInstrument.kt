package com.kinetix.price.persistence

import java.time.Instant

/**
 * Per-instrument staleness record returned by the data-quality endpoint.
 *
 * `ageHours` is the gap between this instrument's freshest price and the
 * platform-wide freshest price across all instruments — not against
 * wall-clock — so the value is meaningful even before the demo tape replay
 * has started moving the universe forward.
 */
data class StaleInstrument(
    val instrumentId: String,
    val lastUpdated: Instant,
    val ageHours: Long,
    val status: String = "STALE",
)
