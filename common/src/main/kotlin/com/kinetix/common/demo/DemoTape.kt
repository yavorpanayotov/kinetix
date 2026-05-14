package com.kinetix.common.demo

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Phase 0 source-of-truth synthesis tape.
 *
 * Single deterministic engine that drives every per-service seeder. Generates a
 * 252-trading-day daily price path for each instrument in the universe with:
 *   - Student-t (df=5) idiosyncratic shocks for fat tails
 *   - GARCH(1,1) volatility clustering
 *   - One-market + sector factor structure (market-beta + sector-beta)
 *   - Regime-driven vol multipliers and drifts (CALM / PRE_STRESS / STRESS-2020 /
 *     STRESS-2022 / RECOVERY)
 *
 * Vol surfaces, rates curves, correlations, P&L, and VaR are all *derived* from this
 * tape — never independently sampled — so consistency checks reconcile by construction.
 *
 * Reproducibility: seeded LCG; same seed produces byte-identical output across JVMs.
 */
class DemoTape(
    val calendar: RegimeCalendar = RegimeCalendar(),
    val seed: Long = DEFAULT_SEED,
    val universe: List<InstrumentTapeSpec> = DemoTapeUniverse.SPECS,
    private val tDof: Int = 5,
    private val garchOmega: Double = 0.000004,
    private val garchAlpha: Double = 0.08,
    private val garchBeta: Double = 0.90,
    private val marketRiskPremium: Double = 0.000125,
) {
    private val days: Int get() = RegimeCalendar.DAYS

    private val factorRng = TapeRng(seed xor 0xFAC107L)
    private val factorReturns: FactorReturns by lazy { synthesizeFactors() }
    private val returnsBySymbol: Map<String, DoubleArray> by lazy { synthesizeReturns() }
    private val pricesBySymbol: Map<String, DoubleArray> by lazy { rollPrices() }
    private val log2BySymbol: Map<String, DoubleArray> by lazy {
        pricesBySymbol.mapValues { (_, prices) -> DoubleArray(prices.size) { ln(prices[it]) } }
    }

    /** Daily return on an instrument. day=0 is the most recent close return relative to day=1. */
    fun dailyReturn(symbol: String, day: Int): Double {
        require(day in 0 until days) { "day must be 0..${days - 1}" }
        return returnsBySymbol[symbol]?.get(day)
            ?: error("Symbol $symbol is not in tape universe")
    }

    /** Closing price on a given day. day=0 is most recent close. */
    fun priceOn(symbol: String, day: Int): Double {
        require(day in 0 until days) { "day must be 0..${days - 1}" }
        return pricesBySymbol[symbol]?.get(day)
            ?: error("Symbol $symbol is not in tape universe")
    }

    /**
     * Trailing realised annualised vol over `window` trading days ending on `endDay`.
     * window typically 20 (for 1M ATM IV calibration) or 60 (longer-term vol regime).
     */
    fun realisedVol(symbol: String, endDay: Int = 0, window: Int = 20): Double {
        require(window >= 5) { "window must be >= 5" }
        val returns = returnsBySymbol[symbol]
            ?: error("Symbol $symbol is not in tape universe")
        // returns is reverse-chronological: index 0 = most recent. So a window
        // ending at endDay covers indices endDay..endDay+window-1.
        val endIdx = endDay
        val startIdx = endDay + window - 1
        if (startIdx >= returns.size) return 0.0
        var sumSq = 0.0
        var n = 0
        var sum = 0.0
        for (i in endIdx..startIdx) {
            sum += returns[i]
            n++
        }
        val mean = sum / n
        for (i in endIdx..startIdx) {
            val d = returns[i] - mean
            sumSq += d * d
        }
        val variance = sumSq / max(1, n - 1)
        return sqrt(variance * 252.0)
    }

    /**
     * Trailing realised correlation between two instruments over `window` trading
     * days ending on `endDay`. Pearson correlation of log-returns.
     */
    fun realisedCorrelation(a: String, b: String, endDay: Int = 0, window: Int = 60): Double {
        require(window >= 5) { "window must be >= 5" }
        if (a == b) return 1.0
        val ra = returnsBySymbol[a] ?: error("Symbol $a not in tape universe")
        val rb = returnsBySymbol[b] ?: error("Symbol $b not in tape universe")
        val endIdx = endDay
        val startIdx = endDay + window - 1
        if (startIdx >= ra.size) return 0.0
        var sa = 0.0
        var sb = 0.0
        var n = 0
        for (i in endIdx..startIdx) { sa += ra[i]; sb += rb[i]; n++ }
        val ma = sa / n
        val mb = sb / n
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in endIdx..startIdx) {
            val xa = ra[i] - ma
            val xb = rb[i] - mb
            cov += xa * xb
            va += xa * xa
            vb += xb * xb
        }
        val denom = sqrt(va * vb)
        return if (denom < 1e-12) 0.0 else cov / denom
    }

    /**
     * Empirical historical VaR at 1-day horizon over a `window`-day trailing sample of
     * returns ending on `endDay` (in reverse-chronological indexing: 0 = most recent).
     *
     * confidence in (0,1) e.g. 0.99 — the returned value is the magnitude of the
     * one-day loss not exceeded with probability `confidence`.
     *
     * `endDay` lets callers slide the window backwards for Kupiec backtesting:
     * estimate VaR from a trailing window, compare it to the *next* day's realised
     * return, then advance.
     */
    fun historicalVaR(
        symbol: String,
        confidence: Double = 0.99,
        window: Int = 252,
        endDay: Int = 0,
    ): Double {
        require(confidence in 0.5..0.999)
        require(window >= 5) { "window must be >= 5" }
        require(endDay >= 0) { "endDay must be >= 0" }
        val returns = returnsBySymbol[symbol] ?: error("Symbol $symbol not in tape universe")
        // returns is reverse-chronological: index 0 = most recent. A window ending at
        // endDay covers indices endDay..endDay+window-1.
        val startIdx = endDay
        val endIdx = (endDay + window).coerceAtMost(returns.size)
        if (startIdx >= returns.size) return 0.0
        val sample = returns.copyOfRange(startIdx, endIdx).sortedArray()
        if (sample.isEmpty()) return 0.0
        val pctIdx = ((1.0 - confidence) * sample.size).toInt().coerceAtLeast(0)
        return -sample[pctIdx]
    }

    fun stressWindows(): List<RegimeCalendar.StressWindow> = calendar.stressWindows()

    // ── synthesis ───────────────────────────────────────────────────────────

    private fun synthesizeFactors(): FactorReturns {
        val market = DoubleArray(days)
        val sector = Sector.values().associateWith { DoubleArray(days) }
        val rates = DoubleArray(days)
        val dollar = DoubleArray(days)

        // Build chronologically (oldest first) so GARCH state can warm up.
        // Reverse-chronological storage is filled at the end.
        val chronoMarket = DoubleArray(days)
        val chronoSector = Sector.values().associateWith { DoubleArray(days) }
        val chronoRates = DoubleArray(days)
        val chronoDollar = DoubleArray(days)

        var marketSigma2 = (MARKET_BASE_DAILY_VOL * MARKET_BASE_DAILY_VOL)
        val sectorSigma2 = Sector.values().associateWith { SECTOR_BASE_DAILY_VOL * SECTOR_BASE_DAILY_VOL }
            .toMutableMap()

        for (chronoIdx in 0 until days) {
            val dayIdx = days - 1 - chronoIdx
            val regime = calendar.regimeForDay(dayIdx)
            val volMult = regime.volMultiplier

            // Market shock — t-distributed, GARCH-scaled, regime-scaled,
            // negative drift during stress so equities draw down realistically.
            val mEps = factorRng.nextStandardisedStudentT(tDof)
            val mDrift = when (regime) {
                Regime.STRESS_2020_ANALOG -> -0.012
                Regime.STRESS_2022_ANALOG -> -0.005
                Regime.PRE_STRESS -> -0.001
                Regime.RECOVERY -> 0.004
                Regime.CALM -> marketRiskPremium
            }
            val mSigmaCalm = sqrt(marketSigma2)
            val mShock = mDrift + mSigmaCalm * volMult * mEps
            chronoMarket[chronoIdx] = mShock
            // GARCH state tracks calm-regime variance — feed it the unscaled shock so
            // regimes don't compound into the persistent vol process.
            val mUnscaledSq = mSigmaCalm * mSigmaCalm * mEps * mEps
            marketSigma2 = garchOmega + garchAlpha * mUnscaledSq + garchBeta * marketSigma2

            // Sector shocks — independent from market, smaller than market vol,
            // regime-amplified.
            for (s in Sector.values()) {
                val eps = factorRng.nextStandardisedStudentT(tDof)
                val sigmaCalm = sqrt(sectorSigma2[s]!!)
                val drift = if (regime == Regime.STRESS_2020_ANALOG && s in stressedSectors) -0.004 else 0.0
                val shock = drift + sigmaCalm * volMult * eps
                chronoSector[s]!![chronoIdx] = shock
                val unscaledSq = sigmaCalm * sigmaCalm * eps * eps
                sectorSigma2[s] = garchOmega + garchAlpha * unscaledSq + garchBeta * sectorSigma2[s]!!
            }

            // Rates — daily 10Y yield change in absolute decimal (e.g. 0.0005 = +5bps).
            // Drift varies by regime (dovish in 2020, hawkish in 2022).
            val rEps = factorRng.nextNormal()
            val rSigma = RATES_BASE_DAILY_SIGMA * (if (regime == Regime.CALM) 1.0 else 1.6)
            chronoRates[chronoIdx] = regime.ratesDrift + rSigma * rEps

            // Dollar index — DXY-like daily log return.
            val dEps = factorRng.nextNormal()
            val dSigma = DOLLAR_BASE_DAILY_VOL * volMult
            val dDrift = when (regime) {
                Regime.STRESS_2020_ANALOG -> 0.002
                Regime.STRESS_2022_ANALOG -> 0.0015
                else -> 0.0
            }
            chronoDollar[chronoIdx] = dDrift + dSigma * dEps
        }

        // Reverse to reverse-chronological storage (index 0 = most recent).
        for (chronoIdx in 0 until days) {
            val dayIdx = days - 1 - chronoIdx
            market[dayIdx] = chronoMarket[chronoIdx]
            for (s in Sector.values()) sector[s]!![dayIdx] = chronoSector[s]!![chronoIdx]
            rates[dayIdx] = chronoRates[chronoIdx]
            dollar[dayIdx] = chronoDollar[chronoIdx]
        }
        return FactorReturns(market, sector, rates, dollar)
    }

    private fun synthesizeReturns(): Map<String, DoubleArray> {
        val marketAnnualVar = MARKET_BASE_DAILY_VOL * MARKET_BASE_DAILY_VOL * 252.0
        val sectorAnnualVar = SECTOR_BASE_DAILY_VOL * SECTOR_BASE_DAILY_VOL * 252.0
        val out = mutableMapOf<String, DoubleArray>()
        for (spec in universe) {
            val rng = TapeRng(seed xor TapeRng.stableSeed(spec.symbol))
            // Decompose configured annualVol into systematic + idiosyncratic so total
            // vol matches the spec rather than being inflated by factor loadings.
            val systematicVar = spec.marketBeta * spec.marketBeta * marketAnnualVar +
                spec.sectorBeta * spec.sectorBeta * sectorAnnualVar
            val totalVar = spec.annualVol * spec.annualVol
            val idioAnnualVar = (totalVar - systematicVar).coerceAtLeast(0.0)
            val idioBaseDaily = if (idioAnnualVar > 0.0)
                sqrt(idioAnnualVar / 252.0) * spec.idiosyncraticVolMultiplier
            else 0.0
            val series = DoubleArray(days)
            // Build chronologically for GARCH stability, then reverse.
            val chrono = DoubleArray(days)
            var sigma2 = idioBaseDaily * idioBaseDaily
            for (chronoIdx in 0 until days) {
                val dayIdx = days - 1 - chronoIdx
                val regime = calendar.regimeForDay(dayIdx)
                val sigmaCalm = sqrt(sigma2)
                val eps = if (idioBaseDaily > 0.0) rng.nextStandardisedStudentT(tDof) else 0.0
                val idio = sigmaCalm * regime.volMultiplier * eps
                val factorPart =
                    spec.marketBeta * factorReturns.market[dayIdx] +
                        spec.sectorBeta * (factorReturns.sector[spec.sector]?.get(dayIdx) ?: 0.0) +
                        spec.ratesSensitivity * factorReturns.rates[dayIdx] +
                        spec.dollarSensitivity * factorReturns.dollar[dayIdx]
                chrono[chronoIdx] = factorPart + idio
                if (idioBaseDaily > 0.0) {
                    val unscaledSq = sigmaCalm * sigmaCalm * eps * eps
                    sigma2 = garchOmega + garchAlpha * unscaledSq + garchBeta * sigma2
                }
            }
            for (chronoIdx in 0 until days) {
                series[days - 1 - chronoIdx] = chrono[chronoIdx]
            }
            out[spec.symbol] = series
        }
        return out
    }

    private fun rollPrices(): Map<String, DoubleArray> {
        // Anchor: priceOn(symbol, 0) == startPrice (the "as-of" close shown today).
        // Walk back in time applying inverse log returns.
        val out = mutableMapOf<String, DoubleArray>()
        for (spec in universe) {
            val returns = returnsBySymbol[spec.symbol]!!
            val prices = DoubleArray(days)
            prices[0] = spec.startPrice
            for (day in 1 until days) {
                // r[day-1] is the return *from day to day-1* (since day=0 is most recent).
                // So price[day] = price[day-1] / exp(r[day-1]).
                val prev = prices[day - 1]
                val r = returns[day - 1]
                val next = prev / exp(r)
                prices[day] = max(0.01, next)
            }
            out[spec.symbol] = prices
        }
        return out
    }

    companion object {
        const val MARKET_BASE_DAILY_VOL: Double = 0.011
        const val SECTOR_BASE_DAILY_VOL: Double = 0.006
        const val RATES_BASE_DAILY_SIGMA: Double = 0.0005
        const val DOLLAR_BASE_DAILY_VOL: Double = 0.004
        const val DEFAULT_SEED: Long = 0x4B696E657469784BL // "KinetixK"

        private val stressedSectors = setOf(Sector.FINANCIALS, Sector.ENERGY, Sector.CONSUMER, Sector.INDUSTRIALS)

        @Suppress("unused")
        private fun nudge(d: Double): Double = if (d == 0.0) 0.0 else d.sign * d
    }
}
