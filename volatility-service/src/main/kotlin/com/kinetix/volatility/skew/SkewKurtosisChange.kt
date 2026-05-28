package com.kinetix.volatility.skew

import kotlin.math.abs

/**
 * Flag a change in the implied-vol surface's third (skew) or fourth
 * (kurtosis) moment that exceeds the configured threshold. Sharp moves
 * in either moment are leading indicators of a regime change — a
 * flattening skew often precedes a re-rating of crash risk — and
 * surfacing them lets the desk re-anchor scenarios early.
 *
 * NaN inputs (a missing point on the surface) are treated as
 * no-change so a stale read does not produce a false alarm.
 */
fun detectSkewKurtosisChange(
    prevSkew: Double,
    currSkew: Double,
    prevKurt: Double,
    currKurt: Double,
    skewThreshold: Double = 0.20,
    kurtosisThreshold: Double = 0.50,
): Boolean {
    if (prevSkew.isNaN() || currSkew.isNaN() || prevKurt.isNaN() || currKurt.isNaN()) {
        return false
    }
    val skewMove = abs(currSkew - prevSkew)
    val kurtMove = abs(currKurt - prevKurt)
    return skewMove >= skewThreshold || kurtMove >= kurtosisThreshold
}
