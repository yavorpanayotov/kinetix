package com.kinetix.correlation.sparse

/**
 * Sparse correlation matrix: missing pairs return `null` rather than
 * a default-zero, so consumers can choose between a factor-model
 * fall-back, a zero-correlation assumption, or skipping the pair
 * entirely. Self-pairs always return 1.0. Symmetric lookup
 * (`correlation(a, b) == correlation(b, a)`).
 */
class SparseCorrelationMatrix(pairs: Map<Pair<String, String>, Double>) {

    private val normalised: Map<Pair<String, String>, Double> = pairs
        .map { (key, value) -> normalise(key) to value }
        .toMap()

    val size: Int get() = normalised.size

    fun correlation(a: String, b: String): Double? {
        if (a == b) return 1.0
        return normalised[normalise(a to b)]
    }

    fun hasPair(a: String, b: String): Boolean {
        if (a == b) return true
        return normalise(a to b) in normalised
    }

    private fun normalise(pair: Pair<String, String>): Pair<String, String> =
        if (pair.first <= pair.second) pair else pair.second to pair.first
}
