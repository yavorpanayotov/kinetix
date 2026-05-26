package com.kinetix.risk.model

import java.time.Instant

data class MarketDataRef(
    val dataType: String,
    val instrumentId: String,
    val assetClass: String,
    val contentHash: String,
    val status: MarketDataSnapshotStatus,
    val sourceService: String,
    val sourcedAt: Instant,
    // True when the risk-engine marked the dependency as required for the
    // calculation. Optional dependencies (best-effort feeds the engine tolerates
    // missing) must not block EOD promotion. Defaults to true to keep the
    // historical strict semantics for callers that don't yet thread this flag.
    val required: Boolean = true,
)

enum class MarketDataSnapshotStatus { FETCHED, MISSING }
