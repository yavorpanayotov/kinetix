package com.kinetix.rates.curve

import java.time.LocalDate

/**
 * Validate a ForwardCurve maturity list against the basic invariants:
 *   - non-empty
 *   - every maturity strictly after [today]
 *   - monotonically increasing (no duplicates, no out-of-order)
 *
 * Past-dated or out-of-order maturities indicate stale or malformed
 * upstream data; letting them propagate would yield NaN discount
 * factors when the pricer integrates over the curve.
 *
 * @throws IllegalArgumentException naming the offending date / index.
 */
fun validateForwardCurveMaturities(today: LocalDate, maturities: List<LocalDate>) {
    require(maturities.isNotEmpty()) {
        "forward curve has no maturities"
    }
    var previous: LocalDate? = null
    for ((index, maturity) in maturities.withIndex()) {
        require(maturity.isAfter(today)) {
            "forward curve maturity at index $index ($maturity) is not strictly after today ($today)"
        }
        if (previous != null) {
            require(maturity.isAfter(previous)) {
                "forward curve maturities are not strictly increasing: $previous followed by $maturity"
            }
        }
        previous = maturity
    }
}
