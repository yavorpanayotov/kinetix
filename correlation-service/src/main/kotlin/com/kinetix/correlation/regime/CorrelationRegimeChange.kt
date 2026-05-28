package com.kinetix.correlation.regime

import kotlin.math.abs

/**
 * Detect a correlation regime change between [previous] and [current]:
 * returns `true` when `|current - previous| >= threshold`. The default
 * 0.3 threshold flags moves that empirically separate "noisy
 * fluctuation" from "everything-correlates-to-1 stress" episodes.
 *
 * NaN inputs are treated as no-change so a missing-pair sentinel
 * does not produce a false alarm.
 */
fun detectCorrelationRegimeChange(
    previous: Double,
    current: Double,
    threshold: Double = 0.3,
): Boolean {
    if (previous.isNaN() || current.isNaN()) return false
    return abs(current - previous) >= threshold
}
