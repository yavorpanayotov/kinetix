package com.kinetix.rates.basis

/** One published cross-currency basis point at a specific tenor. */
data class BasisQuote(
    val baseCurrency: String,
    val quoteCurrency: String,
    val tenorDays: Int,
    val basisBp: Int,
)

/**
 * Cross-currency basis curve: looks up the basis-point spread between
 * the FX-implied and interest-rate-parity-implied forward for a
 * (base, quote, tenor) triple. Symmetric — `(B,A)` basis equals the
 * negation of `(A,B)`. Linear interpolation across tenors when the
 * caller wants a non-quoted point.
 *
 * EUR/USD basis has traded ±50bps in stress; pricing a euro-denominated
 * swap against a flat zero-basis curve produces systematic error during
 * those episodes. This curve carries the per-tenor spread so the
 * pricer applies it correctly.
 */
class MultiCurrencyBasisCurve(quotes: List<BasisQuote>) {

    // Canonical map: (smaller_ccy, larger_ccy, tenor) -> basisBp from the
    // smaller-ccy perspective.
    private val canonical: Map<Triple<String, String, Int>, Int> = quotes
        .map { it.canonicalKey() to it.canonicalBasis() }
        .toMap()

    fun basisBp(base: String, quote: String, tenorDays: Int): Int? {
        if (base == quote) return 0
        val canonicalKey = Triple(minOf(base, quote), maxOf(base, quote), tenorDays)
        val stored = canonical[canonicalKey] ?: return null
        return if (base <= quote) stored else -stored
    }

    /** Linear interpolation between the two surrounding quoted tenors. */
    fun interpolatedBasisBp(base: String, quote: String, tenorDays: Int): Double? {
        if (base == quote) return 0.0
        val (ccyLo, ccyHi) = if (base <= quote) base to quote else quote to base
        val sign = if (base <= quote) 1.0 else -1.0
        val pairTenors = canonical
            .filterKeys { it.first == ccyLo && it.second == ccyHi }
            .mapKeys { it.key.third }
        if (pairTenors.isEmpty()) return null
        val exact = pairTenors[tenorDays]
        if (exact != null) return sign * exact.toDouble()
        val lower = pairTenors.keys.filter { it < tenorDays }.maxOrNull()
        val upper = pairTenors.keys.filter { it > tenorDays }.minOrNull()
        if (lower == null || upper == null) return null
        val w = (tenorDays - lower).toDouble() / (upper - lower).toDouble()
        val interp = pairTenors[lower]!! * (1 - w) + pairTenors[upper]!! * w
        return sign * interp
    }

    private fun BasisQuote.canonicalKey(): Triple<String, String, Int> =
        if (baseCurrency <= quoteCurrency)
            Triple(baseCurrency, quoteCurrency, tenorDays)
        else
            Triple(quoteCurrency, baseCurrency, tenorDays)

    private fun BasisQuote.canonicalBasis(): Int =
        if (baseCurrency <= quoteCurrency) basisBp else -basisBp
}
