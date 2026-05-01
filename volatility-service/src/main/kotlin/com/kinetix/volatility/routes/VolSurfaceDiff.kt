package com.kinetix.volatility.routes

import com.kinetix.common.model.VolPoint
import com.kinetix.volatility.routes.dtos.VolPointDiffDto
import java.math.BigDecimal
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Computes vol differences over the union of two surfaces' (strike, maturity) grids.
 *
 * When a (strike, maturity) point exists in only one surface, the missing vol on the
 * other surface is filled by bilinear interpolation in (log K, sqrt T) coordinates.
 * Points outside the available grid are flat-extrapolated to the nearest boundary
 * knot — extrapolating slopes can produce implausible vols, and the diff is for
 * human review, not pricing.
 *
 * The (log K, sqrt T) coordinate choice reflects the standard surface representation:
 * skew is approximately linear in log-moneyness, and total variance scales linearly
 * in T (so vol scales in sqrt T) along the term structure. Linear interpolation in
 * absolute K and T introduces avoidable curvature errors in both dimensions.
 *
 * Bilinear interpolation in (K, T) space is not arbitrage-free — see the
 * @guidance on `VolSurface.volAt` in market-data.allium. For a comparison
 * display this does not matter, since both surfaces are interpolated under
 * the same scheme and any systematic bias cancels in the difference.
 */
internal fun computeUnionGridDiff(
    basePoints: List<VolPoint>,
    comparePoints: List<VolPoint>,
): List<VolPointDiffDto> {
    val baseMap = basePoints.associateBy { it.strike to it.maturityDays }
    val compareMap = comparePoints.associateBy { it.strike to it.maturityDays }

    val unionGrid: Set<Pair<BigDecimal, Int>> = baseMap.keys + compareMap.keys

    return unionGrid.map { (strike, maturityDays) ->
        val baseVol = baseMap[strike to maturityDays]?.impliedVol
            ?: interpolateVol(strike, maturityDays, basePoints)
        val compareVol = compareMap[strike to maturityDays]?.impliedVol
            ?: interpolateVol(strike, maturityDays, comparePoints)

        VolPointDiffDto(
            strike = strike.toDouble(),
            maturityDays = maturityDays,
            baseVol = baseVol.toDouble(),
            compareVol = compareVol.toDouble(),
            diff = baseVol.toDouble() - compareVol.toDouble(),
        )
    }.sortedWith(compareBy({ it.maturityDays }, { it.strike }))
}

/**
 * Bilinear interpolation in (log K, sqrt T) space, with flat extrapolation outside
 * the convex hull of [points]. Returns the only available vol if there is exactly
 * one knot.
 */
private fun interpolateVol(
    targetStrike: BigDecimal,
    targetMaturityDays: Int,
    points: List<VolPoint>,
): BigDecimal {
    if (points.isEmpty()) return BigDecimal.ZERO
    if (points.size == 1) return points.first().impliedVol

    val targetLogK = ln(targetStrike.toDouble())
    val targetSqrtT = sqrt(targetMaturityDays.toDouble())

    // Bracket maturities: highest distinct maturity ≤ target, lowest ≥ target.
    val maturities = points.map { it.maturityDays }.toSortedSet().toList()
    val (lowerT, upperT) = bracket(maturities, targetMaturityDays)

    val lowerSlice = points.filter { it.maturityDays == lowerT }
    val upperSlice = points.filter { it.maturityDays == upperT }

    val volAtLowerT = interpolateAlongStrike(targetLogK, lowerSlice)
    val volAtUpperT = if (upperT == lowerT) volAtLowerT else interpolateAlongStrike(targetLogK, upperSlice)

    if (upperT == lowerT) return volAtLowerT

    val sqrtLowerT = sqrt(lowerT.toDouble())
    val sqrtUpperT = sqrt(upperT.toDouble())
    val w = ((targetSqrtT - sqrtLowerT) / (sqrtUpperT - sqrtLowerT))
        .coerceIn(0.0, 1.0)

    val interpolated = volAtLowerT.toDouble() + w * (volAtUpperT.toDouble() - volAtLowerT.toDouble())
    return BigDecimal(interpolated.toString())
}

/**
 * Linear interpolation in log-strike along a single-maturity slice. Clamps to
 * boundary knots when the target lies outside the slice's strike range.
 */
private fun interpolateAlongStrike(
    targetLogK: Double,
    slice: List<VolPoint>,
): BigDecimal {
    require(slice.isNotEmpty()) { "Cannot interpolate against an empty slice" }
    if (slice.size == 1) return slice.first().impliedVol

    val sortedByStrike = slice.sortedBy { it.strike }
    val firstLogK = ln(sortedByStrike.first().strike.toDouble())
    val lastLogK = ln(sortedByStrike.last().strike.toDouble())

    if (targetLogK <= firstLogK) return sortedByStrike.first().impliedVol
    if (targetLogK >= lastLogK) return sortedByStrike.last().impliedVol

    // Find bracketing knots.
    for (i in 0 until sortedByStrike.size - 1) {
        val left = sortedByStrike[i]
        val right = sortedByStrike[i + 1]
        val leftLogK = ln(left.strike.toDouble())
        val rightLogK = ln(right.strike.toDouble())
        if (targetLogK in leftLogK..rightLogK) {
            val w = (targetLogK - leftLogK) / (rightLogK - leftLogK)
            val v = left.impliedVol.toDouble() + w * (right.impliedVol.toDouble() - left.impliedVol.toDouble())
            return BigDecimal(v.toString())
        }
    }
    // Fallback (should be unreachable given the boundary checks above).
    return sortedByStrike.last().impliedVol
}

/**
 * Returns the (lower, upper) bracketing values from [sorted] surrounding [target].
 * If [target] is outside the range, both values clamp to the nearest boundary.
 * If [target] sits exactly on a knot, both values equal that knot.
 */
private fun bracket(sorted: List<Int>, target: Int): Pair<Int, Int> {
    if (target <= sorted.first()) return sorted.first() to sorted.first()
    if (target >= sorted.last()) return sorted.last() to sorted.last()
    for (i in 0 until sorted.size - 1) {
        if (target in sorted[i]..sorted[i + 1]) return sorted[i] to sorted[i + 1]
    }
    return sorted.last() to sorted.last()
}
