package com.kinetix.risk.model

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import java.math.BigDecimal

data class PositionRisk(
    val instrumentId: InstrumentId,
    val assetClass: AssetClass,
    val marketValue: BigDecimal,
    val delta: Double?,
    val gamma: Double?,
    val vega: Double?,
    val varContribution: BigDecimal,
    val esContribution: BigDecimal,
    val percentageOfTotal: BigDecimal,
    val instrumentType: String? = null,
    val displayName: String? = null,
    // Per-instrument Theta / Rho. For options the values come from the
    // risk-engine's per-position Black-Scholes output; for linear
    // instruments (cash equity, FX spot, plain bonds, swaps) these
    // surface as explicit zero so the UI distinguishes "computed and
    // zero" from "missing data" (trader-review P0 #2).
    val theta: Double? = null,
    val rho: Double? = null,
    // DV01 (dollar value of a 1bp parallel rates shift). Populated for
    // FIXED_INCOME instruments via an analytical approximation; emits
    // explicit zero on rows where rate sensitivity does not apply.
    val dv01: Double? = null,
)
