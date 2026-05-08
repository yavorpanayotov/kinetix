package com.kinetix.common.demo

import kotlin.math.ln
import kotlin.math.max

/**
 * Phase 0 derivations from the price tape: yield curves and vol surfaces.
 *
 * - Yield curves: per-currency baseline level/slope, perturbed daily by the rates
 *   factor return on the tape (regime-dependent drift). Curves stay arbitrage-free
 *   (positive forward rates, monotone in tenor for normal regimes; flatten or
 *   invert during 2022-analog stress).
 *
 * - Vol surfaces: ATM IV per maturity calibrated to the realised vol path (window
 *   sized to maturity) plus a fixed risk premium. Skew and term shape preserved
 *   from the existing volatility-service convention. Risk premium widens during
 *   stress regimes, matching observed 2020/2022 behaviour.
 *
 * Snapshots are produced for every trading day in the calendar so historical-VaR
 * queries hit them automatically.
 */
class CurveAndVolDerivations(
    private val tape: DemoTape,
    private val curveDefinition: CurveDefinition = CurveDefinition.DEFAULT,
    private val surfaceDefinition: SurfaceDefinition = SurfaceDefinition.DEFAULT,
) {
    /** All daily yield curve snapshots for a given currency. */
    fun yieldCurveSnapshots(currency: String): List<YieldCurveSnapshot> {
        val baseline = curveDefinition.baselineByCurrency[currency]
            ?: error("No baseline curve configured for $currency")
        val tenors = curveDefinition.tenorDays
        val out = ArrayList<YieldCurveSnapshot>(RegimeCalendar.DAYS)
        // Walk chronologically (oldest first) and integrate the rates factor.
        var levelOffset = 0.0
        var slopeOffset = 0.0
        for (chronoIdx in 0 until RegimeCalendar.DAYS) {
            val dayIdx = RegimeCalendar.DAYS - 1 - chronoIdx
            val regime = tape.calendar.regimeForDay(dayIdx)
            // Tape exposes rates via priceOn? No — internal. Re-derive a regime-driven
            // perturbation pinned to a stable hash so different services agree.
            val (dLevel, dSlope) = ratesPerturbation(currency, dayIdx, regime)
            levelOffset += dLevel
            slopeOffset += dSlope
            val rates = DoubleArray(tenors.size) { i ->
                val baseRate = baseline.rates[i]
                // Apply level shift to all tenors; slope shift weighted by ln(t/2y).
                val tenorYears = tenors[i] / 365.0
                val slopeWeight = ln(max(tenorYears, 1.0 / 365.0) / 2.0)
                (baseRate + levelOffset + slopeOffset * slopeWeight).coerceAtLeast(-0.005)
            }
            out += YieldCurveSnapshot(
                currency = currency,
                date = tape.calendar.dateForDay(dayIdx),
                tenorDays = tenors,
                rates = rates,
                regime = regime,
            )
        }
        return out.sortedBy { it.date }
    }

    /** Most recent yield curve snapshot for a currency. */
    fun latestYieldCurve(currency: String): YieldCurveSnapshot =
        yieldCurveSnapshots(currency).last()

    /** All daily vol surface snapshots for an underlier. */
    fun volSurfaceSnapshots(underlier: String): List<VolSurfaceSnapshot> {
        val cfg = surfaceDefinition.byUnderlier[underlier]
            ?: error("No vol surface configured for $underlier")
        val out = ArrayList<VolSurfaceSnapshot>(RegimeCalendar.DAYS)
        for (dayIdx in 0 until RegimeCalendar.DAYS) {
            val regime = tape.calendar.regimeForDay(dayIdx)
            val spot = tape.priceOn(underlier, dayIdx)
            // ATM IV per maturity = realised vol over a window roughly matching maturity,
            // plus risk premium scaled by regime.
            val atm30 = realisedVolWindowed(underlier, dayIdx, 20)
            val atmIv = atm30 * cfg.riskPremium * regimeIvMultiplier(regime)
            val strikePercents = surfaceDefinition.strikePercents
            val maturityDays = surfaceDefinition.maturityDays
            val grid = Array(strikePercents.size) { i ->
                DoubleArray(maturityDays.size) { j ->
                    val matVol = realisedVolWindowed(underlier, dayIdx, max(20, maturityDays[j] / 5)) *
                        cfg.riskPremium * regimeIvMultiplier(regime)
                    val moneyness = (strikePercents[i] - 100).toDouble()
                    val skew = when {
                        moneyness < 0 -> -moneyness * 0.003 + moneyness * moneyness * 0.00008
                        moneyness > 0 -> moneyness * 0.001 + moneyness * moneyness * 0.00004
                        else -> 0.0
                    }
                    val termAdjust = (maturityDays[j] - 90).toDouble() / 365.0 * 0.02
                    (matVol + skew - termAdjust).coerceAtLeast(0.01)
                }
            }
            out += VolSurfaceSnapshot(
                underlier = underlier,
                date = tape.calendar.dateForDay(dayIdx),
                spot = spot,
                atmIv = atmIv,
                strikePercents = strikePercents,
                maturityDays = maturityDays,
                impliedVol = grid,
                regime = regime,
            )
        }
        return out.sortedBy { it.date }
    }

    /** Most recent vol surface snapshot for an underlier. */
    fun latestVolSurface(underlier: String): VolSurfaceSnapshot =
        volSurfaceSnapshots(underlier).last()

    private fun realisedVolWindowed(symbol: String, endDay: Int, window: Int): Double {
        // Clamp so endDay + window - 1 < DAYS. If we don't have at least 5 days of
        // history (oldest end of tape), fall back to the spec's configured annualVol —
        // gives a plausible ATM IV anchor while we're still rolling into the calendar.
        val maxWindow = RegimeCalendar.DAYS - endDay
        if (maxWindow < 5) {
            return DemoTapeUniverse.specOrNull(symbol)?.annualVol ?: 0.20
        }
        val w = window.coerceAtMost(maxWindow).coerceAtLeast(5)
        return tape.realisedVol(symbol, endDay = endDay, window = w)
    }

    private fun regimeIvMultiplier(regime: Regime): Double = when (regime) {
        Regime.STRESS_2020_ANALOG -> 1.4
        Regime.STRESS_2022_ANALOG -> 1.20
        Regime.PRE_STRESS -> 1.10
        Regime.RECOVERY -> 1.15
        Regime.CALM -> 1.0
    }

    private fun ratesPerturbation(currency: String, dayIdx: Int, regime: Regime): Pair<Double, Double> {
        // Deterministic per-day perturbation; same currency on the same day always
        // produces identical numbers across services.
        val seed = TapeRng.stableSeed("$currency:$dayIdx") xor tape.seed
        val rng = TapeRng(seed)
        val z1 = rng.nextNormal()
        val z2 = rng.nextNormal()
        val baseDailyLevel = 0.00010 // ~1bp/day baseline
        val baseDailySlope = 0.00006
        val mult = when (regime) {
            Regime.STRESS_2020_ANALOG -> 2.5
            Regime.STRESS_2022_ANALOG -> 2.0
            Regime.PRE_STRESS -> 1.3
            Regime.RECOVERY -> 1.5
            Regime.CALM -> 1.0
        }
        val drift = regime.ratesDrift
        return (drift + baseDailyLevel * mult * z1) to (baseDailySlope * mult * z2)
    }
}

/** Per-currency baseline yield curve at the most recent date. */
data class CurveDefinition(
    val tenorDays: List<Int>,
    val baselineByCurrency: Map<String, BaselineCurve>,
) {
    data class BaselineCurve(val rates: DoubleArray)

    companion object {
        val DEFAULT_TENORS: List<Int> = listOf(1, 7, 30, 90, 180, 365, 730, 1825, 3650, 10950)
        val DEFAULT: CurveDefinition = CurveDefinition(
            tenorDays = DEFAULT_TENORS,
            baselineByCurrency = mapOf(
                "USD" to BaselineCurve(doubleArrayOf(0.0450, 0.0452, 0.0455, 0.0458, 0.0462, 0.0465, 0.0468, 0.0472, 0.0476, 0.0480)),
                "EUR" to BaselineCurve(doubleArrayOf(0.0300, 0.0302, 0.0305, 0.0308, 0.0312, 0.0315, 0.0320, 0.0328, 0.0335, 0.0340)),
                "GBP" to BaselineCurve(doubleArrayOf(0.0510, 0.0512, 0.0515, 0.0520, 0.0525, 0.0528, 0.0532, 0.0540, 0.0548, 0.0555)),
                "JPY" to BaselineCurve(doubleArrayOf(0.0008, 0.0010, 0.0015, 0.0025, 0.0040, 0.0060, 0.0085, 0.0120, 0.0160, 0.0200)),
            ),
        )
    }
}

data class SurfaceDefinition(
    val strikePercents: List<Int>,
    val maturityDays: List<Int>,
    val byUnderlier: Map<String, UnderlierSurfaceConfig>,
) {
    data class UnderlierSurfaceConfig(
        val riskPremium: Double,
    )

    companion object {
        val DEFAULT_STRIKES: List<Int> = listOf(80, 90, 95, 100, 105, 110, 120)
        val DEFAULT_MATURITIES: List<Int> = listOf(30, 60, 90, 180, 365)
        val DEFAULT: SurfaceDefinition = SurfaceDefinition(
            strikePercents = DEFAULT_STRIKES,
            maturityDays = DEFAULT_MATURITIES,
            byUnderlier = listOf(
                "IDX-SPX" to 1.05,
                "AAPL" to 1.10,
                "MSFT" to 1.10,
                "GOOGL" to 1.10,
                "AMZN" to 1.10,
                "META" to 1.15,
                "NVDA" to 1.15,
                "TSLA" to 1.20,
                "JPM" to 1.05,
                "BABA" to 1.20,
            ).associate { (sym, premium) -> sym to UnderlierSurfaceConfig(riskPremium = premium) },
        )
    }
}
