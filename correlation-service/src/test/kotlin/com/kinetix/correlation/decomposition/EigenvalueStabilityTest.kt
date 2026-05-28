package com.kinetix.correlation.decomposition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * The correlation service decomposes correlation matrices to derive
 * principal components, factor loadings, and the smallest eigenvalue
 * for PSD repair. The decomposition must be deterministic — running
 * it twice on the same matrix must produce eigenvalues that agree to
 * a tight floating-point tolerance — and stable on near-singular
 * matrices (one column nearly equal to another).
 */
class EigenvalueStabilityTest : FunSpec({

    val tolerance = 1e-9

    test("eigenvalues of the 2x2 identity are repeatable [1, 1]") {
        val identity = listOf(listOf(1.0, 0.0), listOf(0.0, 1.0))
        eigenvaluesSymmetric2x2(identity) shouldBe listOf(1.0, 1.0)
    }

    test("eigenvalues of a symmetric matrix are independent of the call site") {
        val m = listOf(listOf(2.0, 1.0), listOf(1.0, 3.0))
        val a = eigenvaluesSymmetric2x2(m)
        val b = eigenvaluesSymmetric2x2(m)
        a shouldBe b
    }

    test("eigenvalues of a near-singular matrix differ from the perfectly-singular case by O(epsilon)") {
        // Perfectly singular: two equal rows -> one eigenvalue = 0, the other = 2.
        val singular = listOf(listOf(1.0, 1.0), listOf(1.0, 1.0))
        val nearSingular = listOf(listOf(1.0 + 1e-10, 1.0), listOf(1.0, 1.0))
        val singularEvs = eigenvaluesSymmetric2x2(singular)
        val nearEvs = eigenvaluesSymmetric2x2(nearSingular)
        // Smallest eigenvalue should be approximately 0 in both.
        abs(singularEvs[0]) shouldBeLessThanOrEqual tolerance
        abs(nearEvs[0]) shouldBeLessThanOrEqual 1e-9
    }

    test("eigenvalues are returned in ascending order") {
        val m = listOf(listOf(5.0, 0.0), listOf(0.0, 2.0))
        eigenvaluesSymmetric2x2(m) shouldBe listOf(2.0, 5.0)
    }
})
