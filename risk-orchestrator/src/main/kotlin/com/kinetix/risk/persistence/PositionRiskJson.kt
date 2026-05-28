package com.kinetix.risk.persistence

import kotlinx.serialization.Serializable

@Serializable
data class PositionRiskJson(
    val instrumentId: String,
    val assetClass: String,
    val marketValue: String,
    val delta: Double?,
    val gamma: Double?,
    val vega: Double?,
    val varContribution: String,
    val esContribution: String,
    val percentageOfTotal: String,
    // Per-instrument Theta / Rho / DV01. Default null so historical
    // snapshots persisted before the trader-review P0 #2 fix still
    // deserialise cleanly when read back.
    val theta: Double? = null,
    val rho: Double? = null,
    val dv01: Double? = null,
)
