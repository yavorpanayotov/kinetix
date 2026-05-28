package com.kinetix.risk.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class PositionRiskDto(
    val instrumentId: String,
    val assetClass: String,
    val marketValue: String,
    val delta: String?,
    val gamma: String?,
    val vega: String?,
    val varContribution: String,
    val esContribution: String,
    val percentageOfTotal: String,
    val instrumentType: String? = null,
    val displayName: String? = null,
    // Per-instrument Theta / Rho / DV01 (trader-review P0 #2). Default
    // null so older callers / persisted snapshots without these fields
    // still deserialise cleanly.
    val theta: String? = null,
    val rho: String? = null,
    val dv01: String? = null,
)
