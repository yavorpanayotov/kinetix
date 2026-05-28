package com.kinetix.position.collateral

import kotlin.math.min

/**
 * Compute the collateral to post against a partially-filled leg of a
 * multi-leg order: `legRequirement * (filledQuantity / totalQuantity)`
 * with defensive guards so a defensive over-fill (`filled > total`)
 * clamps to the full leg requirement instead of over-posting.
 *
 * Mis-reconciling the ratio leaves the desk either over- or under-
 * collateralised on the partially-filled leg, which is the kind of
 * paper cut Operations spends afternoons untangling.
 */
fun reconcilePartialFillCollateral(
    legRequirement: Double,
    filledQuantity: Int,
    totalQuantity: Int,
): Double {
    if (totalQuantity <= 0) return 0.0
    val clampedFilled = min(filledQuantity, totalQuantity).coerceAtLeast(0)
    return legRequirement * clampedFilled.toDouble() / totalQuantity.toDouble()
}
