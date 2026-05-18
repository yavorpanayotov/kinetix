package com.kinetix.correlation.validation

import com.kinetix.common.model.CorrelationMatrix
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Validates that a [CorrelationMatrix] is positive-semi-definite (PSD).
 *
 * A correlation matrix is PSD when all of its eigenvalues are ≥ 0. In practice float64
 * computations leave correlation matrices that are mathematically PSD with a slightly
 * negative smallest eigenvalue on the order of `1e-12`. Callers therefore decide what
 * negative magnitude they consider numerical noise — see [PsdValidationResult.isPsd].
 *
 * Eigenvalues are computed via the cyclic Jacobi rotation algorithm, which is exact
 * for real symmetric matrices and converges quadratically. The matrix is symmetrised
 * (averaging entry `(i,j)` and `(j,i)`) before decomposition to absorb feed-side
 * asymmetry below the validator's tolerance.
 */
object CorrelationPsdValidator {

    fun validate(matrix: CorrelationMatrix): PsdValidationResult {
        val n = matrix.labels.size
        val square = Array(n) { i -> DoubleArray(n) { j -> matrix.values[i * n + j] } }
        return validate(square)
    }

    fun validate(matrix: Array<DoubleArray>): PsdValidationResult {
        val n = matrix.size
        require(n > 0) { "Matrix must be non-empty" }
        require(matrix.all { it.size == n }) { "Matrix must be square" }

        val symmetric = Array(n) { i -> DoubleArray(n) { j -> (matrix[i][j] + matrix[j][i]) / 2.0 } }
        val eigenvalues = jacobiEigenvalues(symmetric)
        val smallest = eigenvalues.min()
        return PsdValidationResult(eigenvalues = eigenvalues, smallestEigenvalue = smallest)
    }

    /**
     * Cyclic Jacobi eigenvalue algorithm for real symmetric `n×n` matrices. Returns the
     * eigenvalues in ascending order. The input matrix is copied; the caller's array is
     * not mutated.
     */
    private fun jacobiEigenvalues(a: Array<DoubleArray>): DoubleArray {
        val n = a.size
        if (n == 1) return doubleArrayOf(a[0][0])

        val m = Array(n) { i -> a[i].copyOf() }

        val maxSweeps = 100
        val convergenceEps = 1e-14

        for (sweep in 0 until maxSweeps) {
            var off = 0.0
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    off += m[p][q] * m[p][q]
                }
            }
            if (sqrt(off) < convergenceEps) break

            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    val apq = m[p][q]
                    if (abs(apq) < convergenceEps) continue

                    val app = m[p][p]
                    val aqq = m[q][q]
                    val theta = (aqq - app) / (2.0 * apq)
                    val t = if (theta >= 0.0) {
                        1.0 / (theta + sqrt(1.0 + theta * theta))
                    } else {
                        1.0 / (theta - sqrt(1.0 + theta * theta))
                    }
                    val c = 1.0 / sqrt(1.0 + t * t)
                    val s = t * c

                    m[p][p] = app - t * apq
                    m[q][q] = aqq + t * apq
                    m[p][q] = 0.0
                    m[q][p] = 0.0

                    for (r in 0 until n) {
                        if (r == p || r == q) continue
                        val arp = m[r][p]
                        val arq = m[r][q]
                        m[r][p] = c * arp - s * arq
                        m[p][r] = m[r][p]
                        m[r][q] = s * arp + c * arq
                        m[q][r] = m[r][q]
                    }
                }
            }
        }

        val eigenvalues = DoubleArray(n) { i -> m[i][i] }
        eigenvalues.sort()
        return eigenvalues
    }
}
