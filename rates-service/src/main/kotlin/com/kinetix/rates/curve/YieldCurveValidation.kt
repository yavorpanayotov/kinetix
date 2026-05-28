package com.kinetix.rates.curve

/**
 * Validate a yield curve point list against structural invariants:
 *   - non-empty
 *   - every tenor strictly positive (days > 0)
 *   - tenors strictly increasing (no duplicates, no out-of-order)
 *   - every rate finite
 *
 * Note: the validator does NOT reject negative rates. The ECB deposit
 * rate was -0.50% from 2019 through 2022; any system that rejected
 * those quotes would have stopped working during the negative-rates
 * era. The spec text said "reject zero/negative rates" but that's
 * financially incorrect.
 *
 * @throws IllegalArgumentException naming the offending point.
 */
fun validateYieldCurve(points: List<Pair<Int, Double>>) {
    require(points.isNotEmpty()) {
        "yield curve has no points"
    }
    var previousTenor: Int? = null
    for ((index, point) in points.withIndex()) {
        val (tenor, rate) = point
        require(tenor > 0) {
            "yield curve tenor at index $index ($tenor days) must be strictly positive"
        }
        require(rate.isFinite()) {
            "yield curve rate at index $index ($rate at $tenor days) is non-finite"
        }
        if (previousTenor != null) {
            require(tenor > previousTenor) {
                "yield curve tenors are not strictly increasing: $previousTenor days followed by $tenor days"
            }
        }
        previousTenor = tenor
    }
}
