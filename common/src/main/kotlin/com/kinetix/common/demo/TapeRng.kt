package com.kinetix.common.demo

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic pseudo-random source for demo synthesis.
 *
 * Reproducibility is non-negotiable: the same seed must produce the same byte-for-byte
 * tape across machines and JVMs so golden fixture tests pin LCG output and CI fails on drift.
 *
 * Uses Knuth's LCG (multiplier 6364136223846793005, increment 1442695040888963407) — same
 * constants as the per-service seeders so existing fixtures remain compatible.
 */
class TapeRng(seed: Long) {
    private var state: Long = seed xor 0x9E3779B97F4A7C15uL.toLong()
    private var spareNormal: Double? = null

    fun nextLong(): Long {
        state = state * 6364136223846793005L + 1442695040888963407L
        return state
    }

    fun nextUniform(): Double {
        // 53-bit uniform in [0, 1)
        val bits = (nextLong() ushr 11) and ((1L shl 53) - 1)
        return bits.toDouble() / (1L shl 53).toDouble()
    }

    /** Standard normal via Box-Muller. Caches the spare draw. */
    fun nextNormal(): Double {
        val cached = spareNormal
        if (cached != null) {
            spareNormal = null
            return cached
        }
        var u1: Double
        do {
            u1 = nextUniform()
        } while (u1 < 1e-12)
        val u2 = nextUniform()
        val r = sqrt(-2.0 * ln(u1))
        val theta = 2.0 * Math.PI * u2
        spareNormal = r * sin(theta)
        return r * cos(theta)
    }

    /**
     * Student-t with integer degrees of freedom.
     * T = Z / sqrt(W/df), W ~ chi2(df), Z ~ N(0,1).
     * df=4 gives the canonical fat-tailed marginal for daily equity returns.
     */
    fun nextStudentT(df: Int): Double {
        require(df >= 2) { "df must be >= 2" }
        val z = nextNormal()
        var chi2 = 0.0
        repeat(df) {
            val n = nextNormal()
            chi2 += n * n
        }
        return z / sqrt(chi2 / df)
    }

    /**
     * Standardised Student-t — variance scaled to 1.
     * For df > 2: variance(t) = df/(df-2), so divide by sqrt(df/(df-2)).
     */
    fun nextStandardisedStudentT(df: Int): Double {
        require(df > 2) { "df must be > 2 for finite variance" }
        return nextStudentT(df) / sqrt(df.toDouble() / (df - 2))
    }

    /** Draw n samples for batch use (price paths, factor shocks). */
    fun nextNormals(n: Int): DoubleArray = DoubleArray(n) { nextNormal() }

    fun nextStandardisedStudentTs(n: Int, df: Int): DoubleArray =
        DoubleArray(n) { nextStandardisedStudentT(df) }

    companion object {
        /**
         * Stable seed derived from a string label. Hash function is intentionally
         * pinned (FNV-1a 64-bit) so seeds do not shift across JVM versions or
         * String.hashCode changes.
         */
        fun stableSeed(label: String): Long {
            var hash = -3750763034362895579L // FNV offset basis
            for (b in label.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (b.toLong() and 0xFFL)
                hash *= 1099511628211L // FNV prime
            }
            return hash
        }
    }
}
