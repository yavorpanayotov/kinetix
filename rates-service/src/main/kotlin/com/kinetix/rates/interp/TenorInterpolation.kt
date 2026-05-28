package com.kinetix.rates.interp

import kotlin.math.exp
import kotlin.math.ln

/**
 * Linear tenor interpolation. Exact knots return the stored value;
 * mid-tenor uses the standard `lo + w * (hi - lo)` form; outside the
 * knot range extrapolates flat (no slope continuation).
 */
fun interpolateLinear(knots: Map<Int, Double>, tenorDays: Int): Double {
    val ordered = knots.entries.sortedBy { it.key }
    val exact = ordered.firstOrNull { it.key == tenorDays }
    if (exact != null) return exact.value
    if (tenorDays <= ordered.first().key) return ordered.first().value
    if (tenorDays >= ordered.last().key) return ordered.last().value
    val lower = ordered.last { it.key < tenorDays }
    val upper = ordered.first { it.key > tenorDays }
    val w = (tenorDays - lower.key).toDouble() / (upper.key - lower.key).toDouble()
    return lower.value + w * (upper.value - lower.value)
}

/**
 * Log-linear tenor interpolation: interp in log-space, exponentiate
 * the result. Common for discount factors / zero rates where the
 * decay between knots is exponential. Flat extrapolation outside.
 */
fun interpolateLogLinear(knots: Map<Int, Double>, tenorDays: Int): Double {
    val ordered = knots.entries.sortedBy { it.key }
    val exact = ordered.firstOrNull { it.key == tenorDays }
    if (exact != null) return exact.value
    if (tenorDays <= ordered.first().key) return ordered.first().value
    if (tenorDays >= ordered.last().key) return ordered.last().value
    val lower = ordered.last { it.key < tenorDays }
    val upper = ordered.first { it.key > tenorDays }
    val w = (tenorDays - lower.key).toDouble() / (upper.key - lower.key).toDouble()
    val logInterp = (1 - w) * ln(lower.value) + w * ln(upper.value)
    return exp(logInterp)
}
