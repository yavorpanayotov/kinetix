package com.kinetix.correlation.validation

/**
 * Validate the range invariant of a correlation matrix: square, with
 * `1.0` on the diagonal and every off-diagonal in `[-1, 1]`. Anything
 * outside those ranges is either bad upstream data (a misnamed
 * covariance entry) or a numerical-conditioning issue (eigen-clip
 * overshoot during PSD repair). The range check is the cheapest
 * sanity gate before the matrix propagates into the risk calc.
 *
 * @throws IllegalArgumentException if the matrix is empty, not
 * square, has a non-1 diagonal, or has an off-diagonal outside the
 * unit range.
 */
fun validateCorrelationMatrixRange(matrix: List<List<Double>>) {
    require(matrix.isNotEmpty()) { "correlation matrix is empty" }
    val n = matrix.size
    for ((i, row) in matrix.withIndex()) {
        require(row.size == n) {
            "correlation matrix is not square — row $i has ${row.size} entries (expected $n)"
        }
        for ((j, value) in row.withIndex()) {
            if (i == j) {
                require(value == 1.0) {
                    "correlation matrix diagonal at ($i,$i) is $value, expected 1.0"
                }
            } else {
                require(value in -1.0..1.0) {
                    "correlation matrix off-diagonal at ($i,$j) is $value, outside [-1, 1]"
                }
            }
        }
    }
}
