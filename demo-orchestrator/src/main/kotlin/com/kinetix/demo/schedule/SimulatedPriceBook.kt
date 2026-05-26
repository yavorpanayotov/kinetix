package com.kinetix.demo.schedule

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random

/**
 * Richer [PriceBook] used in live demos: ships realistic seed prices for
 * every instrument in the canonical demo seed and applies a small Gaussian
 * random walk on every call so prices move tick-to-tick.
 *
 * The walk is multiplicative: `next = previous * (1 + N(0, walkStdDev))`.
 * With the default [DEFAULT_WALK_STDDEV] of `0.003` the per-call move is
 * roughly ±0.3% one-sigma — small enough to stay close to the seed across a
 * trading day, large enough to look alive in the UI. Each instrument's
 * mid is tracked independently so equities and bonds don't drift in
 * lockstep.
 *
 * Determinism is preserved by injecting a seeded [Random] at construction;
 * tests and acceptance fixtures use a fixed seed and a zero
 * [walkStdDev] to recover the exact seed price.
 *
 * Unknown instruments fall back to [fallback], which by default is a
 * [DefaultPriceBook] keyed by asset class.
 *
 * @property random seeded RNG that drives the per-call Gaussian draw. Tests
 *     inject a `Random(seed)` so price sequences are reproducible.
 * @property walkStdDev one-sigma fractional move per call. Default 0.003
 *     (~0.3%). Tests pass 0.0 to recover the exact seed price.
 * @property fallback delegate used when [instrumentId] is not in the seed
 *     table — by default a per-asset-class [DefaultPriceBook].
 */
class SimulatedPriceBook(
    private val random: Random,
    private val walkStdDev: Double = DEFAULT_WALK_STDDEV,
    private val fallback: PriceBook = DefaultPriceBook(),
    seedPrices: Map<String, BigDecimal> = SEED_PRICES,
) : PriceBook {

    /**
     * Per-instrument running mid. Mutated on every call, so this class is
     * not thread-safe — the simulated trader job is single-threaded per
     * tick, which is sufficient for the demo orchestrator.
     */
    private val currentPrices: MutableMap<String, BigDecimal> = seedPrices.toMutableMap()

    override fun priceFor(instrumentId: String, assetClass: String): BigDecimal {
        val seed = currentPrices[instrumentId]
            ?: return fallback.priceFor(instrumentId, assetClass)
        val drifted = applyWalk(seed)
        currentPrices[instrumentId] = drifted
        return drifted
    }

    private fun applyWalk(previous: BigDecimal): BigDecimal {
        if (walkStdDev == 0.0) {
            return previous
        }
        val shock = random.nextGaussian() * walkStdDev
        val multiplier = BigDecimal.ONE + BigDecimal.valueOf(shock)
        // Preserve the seed's scale so that e.g. UST-2Y stays at 2dp.
        val scale = maxOf(previous.scale(), DEFAULT_SCALE)
        return previous.multiply(multiplier).setScale(scale, RoundingMode.HALF_UP)
    }

    companion object {
        /** One-sigma per-call drift — ~0.3% by default. */
        const val DEFAULT_WALK_STDDEV: Double = 0.003

        /** Minimum decimal scale for drifted prices when the seed has fewer dp. */
        private const val DEFAULT_SCALE: Int = 4

        /**
         * Seed prices for every instrument that appears in the canonical
         * demo book profiles. Values are indicative spot prices, expressed
         * in the natural quote convention for each instrument type
         * (clean-price points for govvies, FX rates for FX pairs, USD
         * per-share for cash equities, USD per-contract for listed
         * derivatives).
         */
        val SEED_PRICES: Map<String, BigDecimal> = linkedMapOf(
            // Cash equities — US single stocks.
            "AAPL" to BigDecimal("185.00"),
            "MSFT" to BigDecimal("420.00"),
            "NVDA" to BigDecimal("880.00"),
            "GOOGL" to BigDecimal("155.00"),
            "AMZN" to BigDecimal("225.00"),
            "TSLA" to BigDecimal("245.00"),
            "META" to BigDecimal("590.00"),
            "JNJ" to BigDecimal("155.00"),
            "KO" to BigDecimal("70.00"),
            "PG" to BigDecimal("165.00"),
            // EM ETFs / ADR.
            "EEM" to BigDecimal("50.00"),
            "VWO" to BigDecimal("50.00"),
            "IEMG" to BigDecimal("58.00"),
            "BABA" to BigDecimal("110.00"),
            // FX majors.
            "EURUSD" to BigDecimal("1.085"),
            "GBPUSD" to BigDecimal("1.27"),
            "USDJPY" to BigDecimal("150.0"),
            // US Treasuries — clean-price points (near par).
            "UST-2Y" to BigDecimal("99.20"),
            "UST-5Y" to BigDecimal("98.70"),
            "UST-10Y" to BigDecimal("98.50"),
            "UST-30Y" to BigDecimal("96.80"),
            // Listed derivatives.
            "SPX-OPT-5000C" to BigDecimal("180.0"),
            "ES-FUT-MAR" to BigDecimal("5060.0"),
            "VIX-OPT-20C" to BigDecimal("2.50"),
        )
    }
}
