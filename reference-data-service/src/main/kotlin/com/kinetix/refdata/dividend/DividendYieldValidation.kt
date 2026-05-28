package com.kinetix.refdata.dividend

/**
 * Validate a dividend yield expressed as a fraction (`0.025` == 2.5%).
 *
 * Must be finite, non-negative, and within the fat-finger guard of
 * 50%. Negative yields are bad data (the dividend was likely mis-tagged
 * as a split); above-50% yields are usually a missing-decimal-point
 * fat-finger (a typo turning 2.5% into 25%, then someone fixing it
 * the wrong way). Distress equities with genuine 60%+ yields get a
 * separate manual override path.
 *
 * @throws IllegalArgumentException if [yield] fails any condition.
 */
fun validateDividendYield(yield: Double): Double {
    require(yield.isFinite()) { "dividendYield must be finite (got $yield)" }
    require(yield >= 0.0) { "dividendYield must be non-negative (got $yield)" }
    require(yield <= 0.50) { "dividendYield $yield exceeds the 50% fat-finger guard" }
    return yield
}
