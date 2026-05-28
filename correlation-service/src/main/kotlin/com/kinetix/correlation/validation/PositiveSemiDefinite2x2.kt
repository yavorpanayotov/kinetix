package com.kinetix.correlation.validation

/**
 * Validate that a 2x2 correlation matrix is positive semi-definite.
 *
 * For 2x2 the PSD check reduces to `1 - rho^2 >= 0`, equivalent to
 * `|rho| <= 1`, which is the most common matrix size in pairwise
 * correlation feeds. A non-PSD matrix slipping through would produce
 * "negative variance" results in the VaR Monte-Carlo / Cholesky
 * decomposition; the validator gates the ingestion path.
 *
 * The full N-dimensional case uses eigenvalue checks via the
 * decomposition module — this helper is the cheap, common-case
 * fast path.
 *
 * @throws IllegalArgumentException for any non-2x2 input or for an
 * off-diagonal `|rho| > 1`.
 */
fun validatePositiveSemiDefinite2x2(matrix: List<List<Double>>) {
    require(matrix.size == 2 && matrix.all { it.size == 2 }) {
        "validatePositiveSemiDefinite2x2: expected a 2x2 matrix"
    }
    val rho = matrix[0][1]
    require(rho in -1.0..1.0) {
        "correlation $rho lies outside [-1, 1] — matrix is not PSD"
    }
}
