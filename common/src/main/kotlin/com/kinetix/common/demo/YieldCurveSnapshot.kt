package com.kinetix.common.demo

import java.time.LocalDate

/**
 * A single dated yield curve snapshot derived from the demo tape.
 *
 * Tenors are days-to-maturity; rates are decimals (0.045 = 4.5%).
 */
data class YieldCurveSnapshot(
    val currency: String,
    val date: LocalDate,
    val tenorDays: List<Int>,
    val rates: DoubleArray,
    val regime: Regime,
) {
    init {
        require(tenorDays.size == rates.size) { "tenor and rate arrays must align" }
    }
}
