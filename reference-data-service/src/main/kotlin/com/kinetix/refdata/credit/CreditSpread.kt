package com.kinetix.refdata.credit

/**
 * Validate a credit-spread quote: must be non-negative (a negative
 * spread is either bad data or a referential bug between the spread
 * and its anchor curve), and the [tenorDays] must be one the
 * underlying yield curve actually has so the OAS calc has a real
 * zero-rate to discount against.
 *
 * @throws IllegalArgumentException if [spreadBp] is negative, if
 * [tenorDays] is not in [curveTenorsDays], or if the curve is empty.
 */
fun validateCreditSpread(
    spreadBp: Int,
    tenorDays: Int,
    curveTenorsDays: List<Int>,
): Int {
    require(spreadBp >= 0) {
        "creditSpread: refusing negative spread $spreadBp bps"
    }
    require(curveTenorsDays.isNotEmpty()) {
        "creditSpread: cannot validate tenor against an empty curve"
    }
    require(tenorDays in curveTenorsDays) {
        "creditSpread: tenor $tenorDays days does not match any curve tenor ${curveTenorsDays}"
    }
    return spreadBp
}
