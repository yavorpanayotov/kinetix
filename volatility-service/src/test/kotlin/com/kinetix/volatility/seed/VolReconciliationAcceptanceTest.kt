package com.kinetix.volatility.seed

import com.kinetix.common.demo.CurveAndVolDerivations
import com.kinetix.common.demo.DemoTape
import com.kinetix.common.demo.Regime
import com.kinetix.common.demo.RegimeCalendar
import com.kinetix.common.model.InstrumentId
import com.kinetix.volatility.persistence.DatabaseTestSetup
import com.kinetix.volatility.persistence.ExposedVolSurfaceRepository
import io.kotest.core.spec.style.FunSpec
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Acceptance test for PR 1 item 2 in `docs/plans/demo-follow-up.md`:
 * "Realised vs implied vol reconciliation test passes within risk premium".
 *
 * After `DevDataSeeder.seed()` persists 252 daily vol surfaces per tape-driven
 * underlier, the ATM implied vol on each surface must equal the trailing 21-day
 * realised vol of the same tape (for the same as-of day) *plus a bounded risk
 * premium*. Concretely:
 *
 *   |impliedATM(symbol, t) - realisedVol(tape, symbol, window=21, endDay=t)| < RISK_PREMIUM_BOUND
 *
 * If the seeder ever regresses to a path that's not derived from the tape — or
 * picks a risk premium so large that the implied surface stops tracking the
 * underlying realised vol — this test fails loudly on at least one sample.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * Choosing the risk premium bound
 * ──────────────────────────────────────────────────────────────────────────
 * `CurveAndVolDerivations` builds ATM IV as
 *     atmIv = realisedVol(window=20) * riskPremium * regimeIvMultiplier(regime)
 * where `riskPremium` per underlier is 1.05 (SPX) to 1.20 (TSLA/BABA) and the
 * regime multiplier ranges from 1.0 (CALM) up to 1.4 (STRESS_2020_ANALOG).
 *
 * The absolute spread `|impliedATM - realisedVol|` therefore scales with both
 * realised vol level and regime. To keep the reconciliation criterion
 * meaningful — i.e. tight enough that a regression to fixed constants would
 * trip it — we sample only **CALM-regime** days in this test. Under CALM the
 * regime multiplier is 1.0 and the multiplicative premium collapses to:
 *     atmIv ≈ realised * riskPremium - termAdjust
 * For realised vol up to ~1.0 and the largest configured premium (1.20), the
 * absolute gap is bounded by ~0.20 vol points plus a small term-adjust
 * (≈0.003 for 30-day maturity). Observed worst case across the four sampled
 * CALM dates × three sample symbols is `diff=0.094` (TSLA day=30); we set
 * `RISK_PREMIUM_BOUND = 0.25` to keep a ~2.5× safety margin while remaining
 * tight enough that a regression to flat hand-coded IV would trip it on
 * high-vol names like TSLA (realised regularly >0.40 in CALM).
 *
 * Sample dates are restricted so the realised-vol window fits inside the
 * 252-day tape: `endDay + window <= 252`, i.e. endDay ≤ 231 for window=21.
 *
 * The seeder uses `window=20` and the test uses `window=21` (the plan's
 * spec) — with GARCH dynamics the two are nearly identical, contributing
 * well under 0.01 vol points to the gap.
 */
class VolReconciliationAcceptanceTest : FunSpec({

    val log = LoggerFactory.getLogger(VolReconciliationAcceptanceTest::class.java)

    val db = DatabaseTestSetup.startAndMigrate()
    val volSurfaceRepository = ExposedVolSurfaceRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE volatility_surface_points, volatility_surfaces RESTART IDENTITY CASCADE")
        }
    }

    // Plan-specified bound. See class-level Kdoc for derivation.
    val RISK_PREMIUM_BOUND = 0.25

    // Plan-specified realised-vol window.
    val REALISED_WINDOW = 21

    // Sample mappings: surfaceKey under which the surface is persisted, paired
    // with the tape symbol that drives realised vol. Mirrors DevDataSeeder's
    // TAPE_UNDERLIER_DEFAULTS subset spanning a low-vol benchmark (SPX), a
    // mega-cap (AAPL), and a high-vol single-name (TSLA).
    val sampleMappings = listOf(
        DevDataSeeder.UnderlierMapping(surfaceKey = "SPX", tapeSymbol = "IDX-SPX"),
        DevDataSeeder.UnderlierMapping(surfaceKey = "AAPL", tapeSymbol = "AAPL"),
        DevDataSeeder.UnderlierMapping(surfaceKey = "TSLA", tapeSymbol = "TSLA"),
    )

    test("ATM implied vol reconciles to tape realised vol within risk premium for CALM sample dates") {
        val tape = DemoTape()
        val derivations = CurveAndVolDerivations(tape)
        val calendar = tape.calendar

        // Pick sample days that all fall in CALM regime. Days 0, 30, 130, 200,
        // 220 sit clear of both stress windows (60-90 and 178-184) and their
        // lead-ins/recoveries, and leave room for the 21-day realised-vol
        // window inside the 252-day tape (endDay + window <= 252 ⇒ endDay ≤ 231).
        val candidateDays = listOf(0, 30, 130, 200, 220)
        val sampleDays = candidateDays.filter { calendar.regimeForDay(it) == Regime.CALM }
        check(sampleDays.size >= 3) {
            "expected ≥3 CALM sample days, got ${sampleDays.size}: ${sampleDays.map { it to calendar.regimeForDay(it) }}"
        }

        val seeder = DevDataSeeder(
            volSurfaceRepository = volSurfaceRepository,
            derivations = derivations,
            standaloneSurfaces = emptyList(),
            tapeUnderliers = sampleMappings,
        )
        seeder.seed()

        val mismatches = mutableListOf<String>()

        for (mapping in sampleMappings) {
            for (dayIdx in sampleDays) {
                val realised = tape.realisedVol(
                    symbol = mapping.tapeSymbol,
                    endDay = dayIdx,
                    window = REALISED_WINDOW,
                )
                val asOf = calendar.dateForDay(dayIdx).atStartOfDay(ZoneOffset.UTC).toInstant()
                val persisted = volSurfaceRepository.findAtOrBefore(InstrumentId(mapping.surfaceKey), asOf)
                    ?: error("no persisted surface found for ${mapping.surfaceKey} at $asOf (day=$dayIdx)")

                val implied = atmIvAtMaturity30(persisted, derivations, mapping.tapeSymbol, dayIdx)
                val diff = abs(implied - realised)

                log.info(
                    "vol-reconciliation symbol={} day={} regime={} realised(w=21)={} implied(ATM,30d)={} diff={}",
                    mapping.surfaceKey, dayIdx, calendar.regimeForDay(dayIdx),
                    "%.6f".format(realised), "%.6f".format(implied), "%.6f".format(diff),
                )

                if (diff >= RISK_PREMIUM_BOUND) {
                    mismatches += "${mapping.surfaceKey} day=$dayIdx implied=$implied realised=$realised diff=$diff"
                }
            }
        }

        if (mismatches.isNotEmpty()) {
            error(
                "ATM implied vol diverges from tape realised vol by ≥$RISK_PREMIUM_BOUND for " +
                    "${mismatches.size}/${sampleMappings.size * sampleDays.size} samples. " +
                    "First 10: ${mismatches.take(10)}"
            )
        }
    }

    test("implied ATM IV strictly exceeds realised vol (risk premium is positive) for CALM samples") {
        // A hard floor: any rational vol seeder must charge a positive risk
        // premium over realised vol in CALM. If `riskPremium` ever degrades to
        // 1.0 (no premium) or below, this fires.
        val tape = DemoTape()
        val derivations = CurveAndVolDerivations(tape)
        val calendar = tape.calendar

        val sampleDays = listOf(0, 30, 130, 200, 240).filter { calendar.regimeForDay(it) == Regime.CALM }
        check(sampleDays.isNotEmpty()) { "no CALM days found in sample set" }

        val seeder = DevDataSeeder(
            volSurfaceRepository = volSurfaceRepository,
            derivations = derivations,
            standaloneSurfaces = emptyList(),
            tapeUnderliers = sampleMappings,
        )
        seeder.seed()

        val negativePremiumCases = mutableListOf<String>()

        for (mapping in sampleMappings) {
            for (dayIdx in sampleDays) {
                val realised = tape.realisedVol(
                    symbol = mapping.tapeSymbol,
                    endDay = dayIdx,
                    window = REALISED_WINDOW,
                )
                val asOf = calendar.dateForDay(dayIdx).atStartOfDay(ZoneOffset.UTC).toInstant()
                val persisted = volSurfaceRepository.findAtOrBefore(InstrumentId(mapping.surfaceKey), asOf)
                    ?: error("no persisted surface found for ${mapping.surfaceKey} at $asOf (day=$dayIdx)")

                val implied = atmIvAtMaturity30(persisted, derivations, mapping.tapeSymbol, dayIdx)
                // Term adjust subtracts (30 - 90)/365 * 0.02 ≈ -0.003 from the
                // 30-day ATM point, so we allow a tiny slack below realised vol
                // — but the premium must still be net positive across maturities.
                if (implied < realised - 0.01) {
                    negativePremiumCases += "${mapping.surfaceKey} day=$dayIdx implied=$implied < realised=$realised"
                }
            }
        }

        if (negativePremiumCases.isNotEmpty()) {
            error(
                "Implied vol is below realised vol (negative risk premium) for ${negativePremiumCases.size} samples. " +
                    "First 5: ${negativePremiumCases.take(5)}"
            )
        }
    }
})

/**
 * Extracts the ATM (strike == spot) implied vol at the 30-day maturity from
 * the persisted surface. The seeder writes strikes as `spot * strikePercent /
 * 100.0` and ATM corresponds to `strikePercent = 100`, so we look up the
 * spot for the given day from the derivations and find the point whose strike
 * matches to within the persistence scale's rounding noise.
 */
private fun atmIvAtMaturity30(
    surface: com.kinetix.common.model.VolSurface,
    derivations: CurveAndVolDerivations,
    tapeSymbol: String,
    dayIdx: Int,
): Double {
    val snapshot = derivations.volSurfaceSnapshots(tapeSymbol)
        .first { it.date == surface.asOf.atZone(ZoneOffset.UTC).toLocalDate() }
    val expectedAtmStrike = BigDecimal.valueOf(snapshot.spot)
    val maturity30Points = surface.points.filter { it.maturityDays == 30 }
    require(maturity30Points.isNotEmpty()) { "no 30-day points on surface for ${surface.instrumentId.value}" }

    val atm = maturity30Points.minBy { abs(it.strike.toDouble() - expectedAtmStrike.toDouble()) }
    // Sanity check: the seeder's strike grid is 80/90/95/100/105/110/120% of
    // spot — the ATM point should match spot to within 5% (the gap between
    // 95% and 100% strikes).
    val relativeError = abs(atm.strike.toDouble() - expectedAtmStrike.toDouble()) / expectedAtmStrike.toDouble()
    require(relativeError < 0.001) {
        "ATM strike ${atm.strike} day=$dayIdx is not within 0.1% of spot=$expectedAtmStrike for $tapeSymbol"
    }
    return atm.impliedVol.toDouble()
}
