package com.kinetix.volatility.seed

import com.kinetix.common.demo.CurveAndVolDerivations
import com.kinetix.common.demo.DemoTape
import com.kinetix.common.demo.SurfaceDefinition
import com.kinetix.common.demo.VolSurfaceSnapshot
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import com.kinetix.common.model.VolatilitySource
import com.kinetix.volatility.persistence.VolSurfaceRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset

class DevDataSeeder(
    private val volSurfaceRepository: VolSurfaceRepository,
    private val derivations: CurveAndVolDerivations = SHARED_DERIVATIONS,
    private val standaloneSurfaces: List<StandaloneSurface> = STANDALONE_DEFAULTS,
    private val tapeUnderliers: List<UnderlierMapping> = TAPE_UNDERLIER_DEFAULTS,
) {
    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

    suspend fun seed() {
        val existing = volSurfaceRepository.findLatest(InstrumentId("SPX"))
        if (existing != null) {
            log.info("Volatility data already present, skipping seed")
            return
        }

        log.info(
            "Seeding 252 daily vol surfaces for {} tape-driven underliers + {} standalone",
            tapeUnderliers.size, standaloneSurfaces.size,
        )

        var saves = 0
        for (mapping in tapeUnderliers) {
            val snapshots = derivations.volSurfaceSnapshots(mapping.tapeSymbol)
            for (snap in snapshots) {
                volSurfaceRepository.save(snap.toVolSurface(mapping.surfaceKey))
                saves++
            }
        }
        for (cfg in standaloneSurfaces) {
            volSurfaceRepository.save(cfg.toVolSurface())
            saves++
        }

        log.info("Volatility surface seeding complete — {} surfaces saved", saves)
    }

    /** A surface synthesised independently of the tape (e.g. VIX, no spot in tape). */
    data class StandaloneSurface(
        val underlier: String,
        val spotPrice: Double,
        val atmVol: Double,
    ) {
        fun toVolSurface(asOf: Instant = AS_OF): VolSurface {
            val points = mutableListOf<VolPoint>()
            for (maturityDays in MATURITY_DAYS) {
                for (strikePercent in STRIKE_PERCENTS) {
                    val strike = (spotPrice * strikePercent / 100.0).toBigDecimal()
                        .setScale(2, RoundingMode.HALF_UP)
                    val impliedVol = computeImpliedVol(atmVol, strikePercent, maturityDays)
                    points += VolPoint(strike, maturityDays, impliedVol)
                }
            }
            return VolSurface(
                instrumentId = InstrumentId(underlier),
                asOf = asOf,
                points = points,
                source = VolatilitySource.EXCHANGE,
            )
        }
    }

    /** Mapping from a tape symbol to the surfaceKey we store under. */
    data class UnderlierMapping(
        val surfaceKey: String,
        val tapeSymbol: String,
    )

    companion object {
        val AS_OF: Instant = Instant.parse("2026-02-22T10:00:00Z")
        val STRIKE_PERCENTS: List<Int> = SurfaceDefinition.DEFAULT_STRIKES
        val MATURITY_DAYS: List<Int> = SurfaceDefinition.DEFAULT_MATURITIES

        // Phase 0 shared synthesis — every per-service seeder reads from the same tape.
        internal val SHARED_TAPE: DemoTape = DemoTape()
        internal val SHARED_DERIVATIONS: CurveAndVolDerivations = CurveAndVolDerivations(SHARED_TAPE)

        /**
         * Underliers whose 252 daily vol surfaces are derived from the tape's realised
         * price path + risk premium + regime IV multiplier.
         */
        internal val TAPE_UNDERLIER_DEFAULTS: List<UnderlierMapping> = listOf(
            UnderlierMapping(surfaceKey = "SPX", tapeSymbol = "IDX-SPX"),
            UnderlierMapping(surfaceKey = "AAPL", tapeSymbol = "AAPL"),
            UnderlierMapping(surfaceKey = "MSFT", tapeSymbol = "MSFT"),
            UnderlierMapping(surfaceKey = "GOOGL", tapeSymbol = "GOOGL"),
            UnderlierMapping(surfaceKey = "META", tapeSymbol = "META"),
            UnderlierMapping(surfaceKey = "NVDA", tapeSymbol = "NVDA"),
            UnderlierMapping(surfaceKey = "TSLA", tapeSymbol = "TSLA"),
            UnderlierMapping(surfaceKey = "JPM", tapeSymbol = "JPM"),
            UnderlierMapping(surfaceKey = "BABA", tapeSymbol = "BABA"),
        )

        /**
         * Standalone surfaces — VIX is a vol index, not a price; we keep the existing
         * closed-form skew/term shape and produce a single most-recent snapshot.
         */
        internal val STANDALONE_DEFAULTS: List<StandaloneSurface> = listOf(
            StandaloneSurface(underlier = "VIX", spotPrice = 15.0, atmVol = 0.80),
        )

        // Legacy constant retained for backward compatibility with VolSurfaceFeedSimulator
        // wiring in Application.kt. Built from the most-recent tape snapshot for tape-driven
        // underliers, plus the standalone configs.
        internal data class SurfaceConfig(val spotPrice: Double, val atmVol: Double)

        internal val SURFACE_CONFIGS: Map<String, SurfaceConfig> = run {
            val tape = TAPE_UNDERLIER_DEFAULTS.associate { mapping ->
                val latest = SHARED_DERIVATIONS.latestVolSurface(mapping.tapeSymbol)
                mapping.surfaceKey to SurfaceConfig(spotPrice = latest.spot, atmVol = latest.atmIv)
            }
            val standalone = STANDALONE_DEFAULTS.associate {
                it.underlier to SurfaceConfig(spotPrice = it.spotPrice, atmVol = it.atmVol)
            }
            tape + standalone
        }

        internal fun computeImpliedVol(atmVol: Double, strikePercent: Int, maturityDays: Int): BigDecimal {
            val moneyness = (strikePercent - 100).toDouble()
            val skew = when {
                moneyness < 0 -> -moneyness * 0.003 + moneyness * moneyness * 0.00008
                moneyness > 0 -> moneyness * 0.001 + moneyness * moneyness * 0.00004
                else -> 0.0
            }
            val termAdjust = (maturityDays - 90).toDouble() / 365.0 * 0.02
            val vol = (atmVol + skew - termAdjust).coerceAtLeast(0.01)
            return vol.toBigDecimal().setScale(4, RoundingMode.HALF_UP)
        }

        private fun VolSurfaceSnapshot.toVolSurface(surfaceKey: String): VolSurface {
            val points = mutableListOf<VolPoint>()
            for ((i, strikePct) in strikePercents.withIndex()) {
                for ((j, matDays) in maturityDays.withIndex()) {
                    val strike = (spot * strikePct / 100.0).toBigDecimal(MathContext.DECIMAL128)
                        .setScale(6, RoundingMode.HALF_UP)
                    val iv = impliedVol[i][j].toBigDecimal(MathContext.DECIMAL128)
                        .setScale(6, RoundingMode.HALF_UP)
                    points += VolPoint(strike, matDays, iv)
                }
            }
            return VolSurface(
                instrumentId = InstrumentId(surfaceKey),
                asOf = date.atStartOfDay(ZoneOffset.UTC).toInstant(),
                points = points,
                source = VolatilitySource.EXCHANGE,
            )
        }
    }
}
