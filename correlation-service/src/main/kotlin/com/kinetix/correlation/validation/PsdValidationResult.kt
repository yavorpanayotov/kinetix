package com.kinetix.correlation.validation

/**
 * Outcome of a positive-semi-definite (PSD) validation pass over a correlation matrix.
 *
 * The [tolerance] passed to [isPsd] is the magnitude of a *negative* eigenvalue we are
 * willing to attribute to float64 numerical noise. The default `1e-6` matches the
 * conventional floor for n×n correlation matrices computed in double precision.
 */
data class PsdValidationResult(
    val eigenvalues: DoubleArray,
    val smallestEigenvalue: Double,
) {
    fun isPsd(tolerance: Double = 1e-6): Boolean = smallestEigenvalue >= -tolerance

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PsdValidationResult) return false
        if (!eigenvalues.contentEquals(other.eigenvalues)) return false
        if (smallestEigenvalue != other.smallestEigenvalue) return false
        return true
    }

    override fun hashCode(): Int {
        var result = eigenvalues.contentHashCode()
        result = 31 * result + smallestEigenvalue.hashCode()
        return result
    }
}
