package com.kinetix.common.demo

import kotlin.math.max
import kotlin.math.pow

/**
 * Deterministic sampling primitives for the demo trade tape.
 *
 * Power-law instrument selection (top 20% → 80% of flow), regime-aware daily
 * trade counts, momentum-driven side bias, and intraday timestamp bucketing.
 *
 * All methods take a [TapeRng] explicitly so the seeder can pin a stable seed
 * and produce byte-identical tapes for golden fixture tests.
 */
object TradeTapeSampler {

    /**
     * Build Zipfian weights over `n` items so the top ~20% account for ~80% of mass.
     * weights are unnormalised; pass to [sampleWeightedIndex].
     */
    fun zipfWeights(n: Int, alpha: Double = 1.4): DoubleArray {
        require(n > 0) { "n must be positive" }
        return DoubleArray(n) { i -> 1.0 / (i + 1.0).pow(alpha) }
    }

    /** Sample an index in [0, weights.size) with probability proportional to weights[i]. */
    fun sampleWeightedIndex(rng: TapeRng, weights: DoubleArray): Int {
        require(weights.isNotEmpty()) { "weights must not be empty" }
        var sum = 0.0
        for (w in weights) sum += w
        require(sum > 0) { "weights must sum to a positive value" }
        val u = rng.nextUniform() * sum
        var acc = 0.0
        for (i in weights.indices) {
            acc += weights[i]
            if (u < acc) return i
        }
        return weights.size - 1
    }

    /**
     * Pick a side with momentum: an autocorrelation-1 process on the latent
     * direction signal. `prevSignal` carries persistent flow bias; we update with a
     * fresh draw and return the resulting side.
     *
     * Returns (newSignal, isBuy).
     */
    fun nextSideWithMomentum(
        rng: TapeRng,
        prevSignal: Double,
        autocorrelation: Double = 0.55,
        baseBuyBias: Double = 0.10,
    ): Pair<Double, Boolean> {
        require(autocorrelation in 0.0..0.99) { "autocorrelation must be in [0, 0.99]" }
        val shock = rng.nextNormal()
        val newSignal = autocorrelation * prevSignal + (1 - autocorrelation) * shock
        val pBuy = (0.5 + baseBuyBias + 0.15 * newSignal).coerceIn(0.05, 0.95)
        val isBuy = rng.nextUniform() < pBuy
        return newSignal to isBuy
    }

    /**
     * Daily trade count for a book on a given regime day.
     * - calm: Poisson-ish around `baseRate`
     * - pre-stress: 1.4× (positioning ahead of event)
     * - 2020-analog: 2.5× (high turnover at vol spike)
     * - 2022-analog: 1.7× (positioning during drawdown)
     * - recovery: 1.3×
     *
     * Adds a small day-of-week tilt: Monday/Friday tends to have ~10% lower volume.
     */
    fun dailyTradeCount(
        rng: TapeRng,
        baseRate: Int,
        regime: Regime,
        dayOfWeek: Int,
    ): Int {
        val regimeMult = when (regime) {
            Regime.CALM -> 1.0
            Regime.PRE_STRESS -> 1.4
            Regime.STRESS_2020_ANALOG -> 2.5
            Regime.STRESS_2022_ANALOG -> 1.7
            Regime.RECOVERY -> 1.3
        }
        val dowMult = when (dayOfWeek) {
            1, 5 -> 0.90 // Mon/Fri lighter
            else -> 1.0
        }
        val mean = baseRate * regimeMult * dowMult
        // Approximate Poisson via normal w/ floor at 0; not exact but adequate for demo.
        val draw = mean + rng.nextNormal() * (mean * 0.20)
        return max(1, draw.toInt())
    }

    /** Intraday seconds-from-midnight bucket favouring open and close. */
    fun intradaySeconds(rng: TapeRng, isEuropean: Boolean = false): Long {
        if (isEuropean) {
            return 28800L + (rng.nextUniform() * 10800).toLong()
        }
        val u = rng.nextUniform()
        return when {
            u < 0.35 -> 52200L + (rng.nextUniform() * 3600).toLong()  // open hour heavy
            u < 0.50 -> 57600L + (rng.nextUniform() * 7200).toLong()  // late morning
            u < 0.65 -> 64800L + (rng.nextUniform() * 7200).toLong()  // afternoon
            else      -> 72000L + (rng.nextUniform() * 3600).toLong() // close hour heavy
        }
    }
}
