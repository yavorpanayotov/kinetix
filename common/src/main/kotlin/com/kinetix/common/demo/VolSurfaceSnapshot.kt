package com.kinetix.common.demo

import java.time.LocalDate

/**
 * A single dated vol surface snapshot derived from the demo tape.
 *
 * impliedVol[i, j] = annualised IV at strikePercent[i], maturityDays[j].
 * spot/ATM IV are derived from the underlying tape's realised path so the
 * surface reconciles with the price history (within a configurable risk premium).
 */
data class VolSurfaceSnapshot(
    val underlier: String,
    val date: LocalDate,
    val spot: Double,
    val atmIv: Double,
    val strikePercents: List<Int>,
    val maturityDays: List<Int>,
    val impliedVol: Array<DoubleArray>, // [strike][maturity]
    val regime: Regime,
) {
    init {
        require(impliedVol.size == strikePercents.size) { "impliedVol rows must equal strikePercents.size" }
        for (row in impliedVol) {
            require(row.size == maturityDays.size) { "impliedVol cols must equal maturityDays.size" }
        }
    }
}
