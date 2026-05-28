package com.kinetix.correlation.decomposition

import kotlin.math.sqrt

/**
 * Compute the two real eigenvalues of a 2x2 symmetric matrix, returned
 * in ascending order. Uses the closed-form quadratic so the result is
 * deterministic and avoids the iterative methods' tolerance settings.
 *
 * The closed form is `lambda = (trace +/- sqrt(trace^2 - 4*det)) / 2`,
 * which is numerically stable when the matrix is well-conditioned and
 * gracefully degenerate when it is nearly singular (the discriminant
 * approaches `trace^2`, so the smaller eigenvalue approaches 0).
 *
 * Useful for the smallest-eigenvalue check that PSD repair uses.
 */
fun eigenvaluesSymmetric2x2(m: List<List<Double>>): List<Double> {
    require(m.size == 2 && m[0].size == 2 && m[1].size == 2) {
        "eigenvaluesSymmetric2x2: matrix must be 2x2"
    }
    val a = m[0][0]
    val b = m[0][1]
    val d = m[1][1]
    // Symmetry: m[1][0] == b.
    val trace = a + d
    val det = a * d - b * b
    val discriminant = trace * trace - 4.0 * det
    val sqrtDisc = if (discriminant > 0.0) sqrt(discriminant) else 0.0
    val lo = (trace - sqrtDisc) / 2.0
    val hi = (trace + sqrtDisc) / 2.0
    return listOf(lo, hi)
}
