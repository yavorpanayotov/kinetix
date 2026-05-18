package com.kinetix.testsupport.builders

import com.kinetix.common.model.CorrelationMatrix
import com.kinetix.common.model.EstimationMethod
import java.time.Instant
import java.util.Random
import kotlin.math.sqrt

/**
 * Test fixture factory for [CorrelationMatrix]. Two named constructors cover
 * the cases that come up overwhelmingly often in tests: the identity matrix
 * (instruments are uncorrelated with each other, perfectly correlated with
 * themselves) and a deterministic random matrix that is positive
 * semi-definite by construction.
 *
 * Both factories return a fully valid [CorrelationMatrix] with sensible
 * defaults for the metadata fields ([labels], [windowDays], [asOfDate],
 * [EstimationMethod]). The labels default to `INST-1`, `INST-2`, ... so the
 * dimension is always obvious in test failures.
 *
 * Example:
 * ```
 * val identity = TestCorrelationMatrix.identity(4)
 * val random   = TestCorrelationMatrix.random(n = 4, seed = 42L)
 * ```
 *
 * The matrices produced here are by design PSD — [random] uses the standard
 * `C = A·Aᵀ` construction followed by normalisation of the diagonal to 1,
 * so it composes nicely with `CorrelationPsdValidator` for assertions.
 */
object TestCorrelationMatrix {

    /** Default observation window for fixture matrices, in trading days. */
    const val DEFAULT_WINDOW_DAYS: Int = 60

    /** Default `as-of` instant for fixture matrices (deterministic, stable). */
    val DEFAULT_AS_OF: Instant = Instant.parse("2026-01-01T00:00:00Z")

    /** Default estimation method for fixture matrices. */
    val DEFAULT_METHOD: EstimationMethod = EstimationMethod.HISTORICAL

    /**
     * Returns the `n×n` identity correlation matrix: `1.0` on the diagonal,
     * `0.0` elsewhere. Trivially symmetric and PSD.
     *
     * @throws IllegalArgumentException if [n] is less than one.
     */
    fun identity(n: Int): CorrelationMatrix {
        require(n >= 1) { "Matrix dimension must be at least 1, got $n" }
        val values = DoubleArray(n * n)
        for (i in 0 until n) {
            values[i * n + i] = 1.0
        }
        return CorrelationMatrix(
            labels = defaultLabels(n),
            values = values.toList(),
            windowDays = DEFAULT_WINDOW_DAYS,
            asOfDate = DEFAULT_AS_OF,
            method = DEFAULT_METHOD,
        )
    }

    /**
     * Returns an `n×n` PSD correlation matrix generated deterministically
     * from [seed]. The same `(n, seed)` pair always produces the same
     * matrix.
     *
     * Construction: draw a random `n×n` factor matrix `A` with entries from
     * `Random(seed).nextGaussian()`, compute `C = A·Aᵀ` (positive
     * semi-definite by construction), then normalise so every diagonal
     * entry is exactly `1.0`. The resulting matrix is symmetric, has a
     * unit diagonal, and is PSD — i.e. a valid correlation matrix.
     *
     * @throws IllegalArgumentException if [n] is less than one.
     */
    fun random(n: Int, seed: Long): CorrelationMatrix {
        require(n >= 1) { "Matrix dimension must be at least 1, got $n" }

        val random = Random(seed)
        // Factor matrix A is n×n. nextGaussian() is deterministic given the seed
        // and produces a well-conditioned C = A·Aᵀ for n ≥ 1.
        val a = Array(n) { DoubleArray(n) { random.nextGaussian() } }

        // C = A · Aᵀ
        val c = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                var sum = 0.0
                for (k in 0 until n) {
                    sum += a[i][k] * a[j][k]
                }
                c[i][j] = sum
            }
        }

        // Normalise to unit diagonal: C_ij ← C_ij / sqrt(C_ii · C_jj).
        // For n=1 the diagonal entry is positive (squared Gaussian), so the
        // sqrt is well-defined. The diagonal becomes exactly 1.0 after
        // dividing C_ii by sqrt(C_ii · C_ii) = C_ii.
        val diagSqrt = DoubleArray(n) { sqrt(c[it][it]) }
        val values = DoubleArray(n * n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                values[i * n + j] = if (i == j) 1.0 else c[i][j] / (diagSqrt[i] * diagSqrt[j])
            }
        }

        return CorrelationMatrix(
            labels = defaultLabels(n),
            values = values.toList(),
            windowDays = DEFAULT_WINDOW_DAYS,
            asOfDate = DEFAULT_AS_OF,
            method = DEFAULT_METHOD,
        )
    }

    private fun defaultLabels(n: Int): List<String> = List(n) { "INST-${it + 1}" }
}
