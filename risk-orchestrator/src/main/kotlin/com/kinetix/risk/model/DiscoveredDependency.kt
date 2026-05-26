package com.kinetix.risk.model

data class DiscoveredDependency(
    val dataType: String,
    val instrumentId: String,
    val assetClass: String,
    val parameters: Map<String, String> = emptyMap(),
    // Mirrors the risk-engine's MarketDataDependency.required flag — when false
    // the engine tolerates a fetch failure and consumers should not gate on it
    // (e.g. EOD promotion). Defaults to true so existing call sites preserve the
    // previous "everything is required" behaviour until updated.
    val required: Boolean = true,
)
