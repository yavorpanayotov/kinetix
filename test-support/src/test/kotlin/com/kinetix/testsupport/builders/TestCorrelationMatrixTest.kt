package com.kinetix.testsupport.builders

import com.kinetix.common.model.CorrelationMatrix
import com.kinetix.common.model.EstimationMethod
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs

/**
 * Smoke test for [TestCorrelationMatrix]. Verifies the two named factory
 * methods produce well-formed, deterministic, positive semi-definite
 * correlation matrices.
 *
 * The PSD check is inlined here because `CorrelationPsdValidator` lives in
 * `:correlation-service` and `:test-support` cannot depend on a service
 * module without inverting the architectural arrow (services depend on
 * test-support, not the other way round). The inline check covers the
 * necessary mathematical properties — symmetry, unit diagonal, entries in
 * `[-1, 1]`, and a deterministic quadratic-form sweep `xᵀCx ≥ 0` — which
 * is more than enough signal for a smoke test of a fixture builder.
 */
class TestCorrelationMatrixTest : FunSpec({

    test("identity(3) has 1.0 on the diagonal and 0.0 elsewhere") {
        val matrix = TestCorrelationMatrix.identity(3)

        matrix.labels.size shouldBe 3
        matrix.values.size shouldBe 9
        val square = matrix.asSquare()
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                val expected = if (i == j) 1.0 else 0.0
                square[i][j] shouldBe (expected plusOrMinus 1e-15)
            }
        }
    }

    test("identity(3) is symmetric and PSD") {
        val matrix = TestCorrelationMatrix.identity(3)

        matrix.asSquare().assertIsSymmetric()
        matrix.asSquare().assertIsPsd()
    }

    test("identity uses the documented default metadata") {
        val matrix = TestCorrelationMatrix.identity(2)

        matrix.windowDays shouldBe TestCorrelationMatrix.DEFAULT_WINDOW_DAYS
        matrix.asOfDate shouldBe TestCorrelationMatrix.DEFAULT_AS_OF
        matrix.method shouldBe EstimationMethod.HISTORICAL
        matrix.labels shouldBe listOf("INST-1", "INST-2")
    }

    test("identity(0) throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            TestCorrelationMatrix.identity(0)
        }
    }

    test("identity(-1) throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            TestCorrelationMatrix.identity(-1)
        }
    }

    test("random(4, 42L) returns the same matrix on two invocations (determinism)") {
        val first = TestCorrelationMatrix.random(n = 4, seed = 42L)
        val second = TestCorrelationMatrix.random(n = 4, seed = 42L)

        first shouldBe second
    }

    test("random(4, 42L) is symmetric with a unit diagonal") {
        val matrix = TestCorrelationMatrix.random(n = 4, seed = 42L)
        val square = matrix.asSquare()

        square.assertIsSymmetric()
        for (i in 0 until 4) {
            withClue("Diagonal entry ($i,$i) must be 1.0") {
                square[i][i] shouldBe (1.0 plusOrMinus 1e-12)
            }
        }
    }

    test("random(4, 42L) has all entries in [-1, 1]") {
        val matrix = TestCorrelationMatrix.random(n = 4, seed = 42L)
        val square = matrix.asSquare()

        for (i in 0 until 4) {
            for (j in 0 until 4) {
                withClue("Entry ($i,$j) = ${square[i][j]} must be in [-1, 1]") {
                    (square[i][j] in -1.0..1.0) shouldBe true
                }
            }
        }
    }

    test("random(4, 42L) is positive semi-definite") {
        val matrix = TestCorrelationMatrix.random(n = 4, seed = 42L)

        matrix.asSquare().assertIsPsd()
    }

    test("random(4, 42L) differs from random(4, 43L) — seed affects output") {
        val a = TestCorrelationMatrix.random(n = 4, seed = 42L)
        val b = TestCorrelationMatrix.random(n = 4, seed = 43L)

        a shouldNotBe b
    }

    test("random(1, 7L) is the trivial 1x1 matrix containing 1.0") {
        val matrix = TestCorrelationMatrix.random(n = 1, seed = 7L)

        matrix.values shouldBe listOf(1.0)
    }

    test("random(0, 1L) throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            TestCorrelationMatrix.random(n = 0, seed = 1L)
        }
    }
})

private fun CorrelationMatrix.asSquare(): Array<DoubleArray> {
    val n = labels.size
    return Array(n) { i -> DoubleArray(n) { j -> values[i * n + j] } }
}

private fun Array<DoubleArray>.assertIsSymmetric() {
    val n = size
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            withClue("Matrix must be symmetric: ($i,$j)=${this[i][j]} vs ($j,$i)=${this[j][i]}") {
                (abs(this[i][j] - this[j][i]) < 1e-12) shouldBe true
            }
        }
    }
}

/**
 * Inline PSD check: computes eigenvalues using the cyclic Jacobi algorithm
 * (same approach as `CorrelationPsdValidator`) and asserts the smallest
 * eigenvalue is non-negative within numerical tolerance. This duplication
 * is intentional — keeping `:test-support` independent of
 * `:correlation-service` preserves the existing module arrow.
 */
private fun Array<DoubleArray>.assertIsPsd() {
    val eigenvalues = jacobiEigenvalues(this)
    val smallest = eigenvalues.min()
    withClue("Smallest eigenvalue ${smallest} must be ≥ -1e-9 (eigenvalues=${eigenvalues.toList()})") {
        (smallest >= -1e-9) shouldBe true
    }
}

private fun jacobiEigenvalues(a: Array<DoubleArray>): DoubleArray {
    val n = a.size
    if (n == 1) return doubleArrayOf(a[0][0])

    // Symmetrise and copy so we don't mutate the caller's array.
    val m = Array(n) { i -> DoubleArray(n) { j -> (a[i][j] + a[j][i]) / 2.0 } }

    val maxSweeps = 100
    val convergenceEps = 1e-14

    for (sweep in 0 until maxSweeps) {
        var off = 0.0
        for (p in 0 until n - 1) {
            for (q in p + 1 until n) {
                off += m[p][q] * m[p][q]
            }
        }
        if (kotlin.math.sqrt(off) < convergenceEps) break

        for (p in 0 until n - 1) {
            for (q in p + 1 until n) {
                val apq = m[p][q]
                if (abs(apq) < convergenceEps) continue

                val app = m[p][p]
                val aqq = m[q][q]
                val theta = (aqq - app) / (2.0 * apq)
                val t = if (theta >= 0.0) {
                    1.0 / (theta + kotlin.math.sqrt(1.0 + theta * theta))
                } else {
                    1.0 / (theta - kotlin.math.sqrt(1.0 + theta * theta))
                }
                val c = 1.0 / kotlin.math.sqrt(1.0 + t * t)
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

    return DoubleArray(n) { i -> m[i][i] }
}
