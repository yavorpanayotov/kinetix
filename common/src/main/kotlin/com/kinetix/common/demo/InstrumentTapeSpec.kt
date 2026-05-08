package com.kinetix.common.demo

import com.kinetix.common.model.AssetClass

/**
 * Per-instrument metadata that drives synthesis: factor loadings, base vol, sector,
 * starting price, and regime sensitivity. Hardcoded for the demo universe — production
 * code never reads from here.
 */
data class InstrumentTapeSpec(
    val symbol: String,
    val assetClass: AssetClass,
    val sector: Sector,
    val currency: String,
    val startPrice: Double,
    val annualVol: Double,
    val marketBeta: Double,
    val sectorBeta: Double,
    val ratesSensitivity: Double = 0.0,
    val dollarSensitivity: Double = 0.0,
    val idiosyncraticVolMultiplier: Double = 1.0,
    val priceScale: Int = 2,
)
