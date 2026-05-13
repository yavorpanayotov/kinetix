package com.kinetix.risk.seed

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.model.AttributionDataQuality
import com.kinetix.risk.model.CounterpartyExposureSnapshot
import com.kinetix.risk.model.ExposureAtTenor
import com.kinetix.risk.model.NettingSetExposure
import com.kinetix.risk.model.PnlAttribution
import com.kinetix.risk.model.PositionPnlAttribution
import com.kinetix.risk.model.RunStatus
import com.kinetix.risk.model.TriggerType
import com.kinetix.risk.model.ValuationJob
import com.kinetix.risk.persistence.CounterpartyExposureRepository
import com.kinetix.risk.persistence.PnlAttributionRepository
import com.kinetix.risk.service.ValuationJobRecorder
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.sin

class DevDataSeeder(
    private val jobRecorder: ValuationJobRecorder,
    private val exposureRepository: CounterpartyExposureRepository? = null,
    private val pnlAttributionRepository: PnlAttributionRepository? = null,
) {
    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

    suspend fun seed() {
        seedVaRTimeline()
        seedCounterpartyExposures()
        seedPnlAttribution()
    }

    private suspend fun seedVaRTimeline() {
        val existing = jobRecorder.findByTriggeredBy("SEED")
        if (existing.isNotEmpty()) {
            // SEED data uses timestamps relative to Instant.now() at seed time.
            // On restart, delete stale entries and recreate with current timestamps
            // so the UI's default "Last 24h" view always has data.
            log.info("Deleting {} stale SEED entries before re-seeding", existing.size)
            jobRecorder.deleteByTriggeredBy("SEED")
        }

        val jobs = buildSeedJobs()
        log.info("Seeding {} VaR timeline entries across {} books", jobs.size, BOOK_VAR_PROFILES.size)

        for (job in jobs) {
            jobRecorder.save(job)
        }

        log.info("VaR timeline seeding complete")
    }

    /**
     * Phase 3 Gap 9 — bake an EOD P&L attribution example into each book.
     *
     * The single most-asked question in any institutional demo:
     * "My book is down $X today — break it down: delta, gamma, vega, theta,
     * residual." Every book gets one yesterday-dated row with a hand-tuned
     * decomposition. Position-level rows sum exactly to book totals, and
     * book totals sum exactly to delta + gamma + vega + theta + rho +
     * cross-Greek terms + unexplained — so the demo presenter can pull up
     * the book row, the position grid, and the totals line and the numbers
     * tie out in front of the buyer.
     */
    private suspend fun seedPnlAttribution() {
        val repo = pnlAttributionRepository ?: return

        val anchorDate = LocalDate.now(ZoneOffset.UTC).minusDays(1)
        val anchor = repo.findByBookIdAndDate(BookId("equity-growth"), anchorDate)
        if (anchor != null) {
            log.info("P&L attribution seed data already present, skipping")
            return
        }

        val attributions = buildPnlAttributions(anchorDate)
        log.info("Seeding {} P&L attribution rows across {} books",
            attributions.size, attributions.size)

        for (attribution in attributions) {
            repo.save(attribution)
        }

        log.info("P&L attribution seeding complete")
    }

    private suspend fun seedCounterpartyExposures() {
        if (exposureRepository == null) return

        val existing = exposureRepository.findLatestForAllCounterparties()
        if (existing.isNotEmpty()) {
            log.info("Counterparty exposure seed data already present ({} rows), skipping", existing.size)
            return
        }

        val snapshots = buildCounterpartyExposureSnapshots()
        log.info("Seeding {} counterparty exposure snapshots", snapshots.size)

        for (snapshot in snapshots) {
            exposureRepository.save(snapshot)
        }

        log.info("Counterparty exposure seeding complete")
    }

    companion object {
        private data class VaRProfile(
            val bookId: String,
            val baseVaR: Double,
            val volatilityPct: Double,
            val baseES: Double,
        )

        private val BOOK_VAR_PROFILES = listOf(
            VaRProfile("equity-growth", 4_800_000.0, 0.08, 5_900_000.0),
            VaRProfile("tech-momentum", 4_200_000.0, 0.10, 5_100_000.0),
            VaRProfile("emerging-markets", 3_600_000.0, 0.12, 4_400_000.0),
            VaRProfile("fixed-income", 1_800_000.0, 0.04, 2_200_000.0),
            VaRProfile("multi-asset", 6_500_000.0, 0.07, 7_900_000.0),
            VaRProfile("macro-hedge", 4_100_000.0, 0.09, 5_000_000.0),
            VaRProfile("balanced-income", 2_200_000.0, 0.05, 2_700_000.0),
            VaRProfile("derivatives-book", 5_800_000.0, 0.11, 7_100_000.0),
        )

        private const val HISTORY_DAYS = 30
        private const val INTRADAY_HOURS = 8

        private fun deterministicJitter(bookIndex: Int, dayIndex: Int, hourIndex: Int): Double {
            val seed = bookIndex * 1000.0 + dayIndex * 10.0 + hourIndex
            return sin(seed * 0.7) * 0.5 + sin(seed * 1.3) * 0.3 + sin(seed * 2.1) * 0.2
        }

        private data class CounterpartyProfile(
            val counterpartyId: String,
            val currentNetExposure: Double,
            val peakPfe: Double,
            val cva: Double?,
            val collateralHeld: Double,
            val collateralPosted: Double,
            val nettingSetId: String,
            val agreementType: String,
        )

        private val COUNTERPARTY_PROFILES = listOf(
            CounterpartyProfile("CP-GS", 2_000_000.0, 1_800_000.0, 12_500.0, 500_000.0, 150_000.0, "NS-GS-001", "ISDA"),
            CounterpartyProfile("CP-JPM", 6_500_000.0, 7_200_000.0, 45_000.0, 1_200_000.0, 300_000.0, "NS-JPM-001", "ISDA"),
            CounterpartyProfile("CP-BARC", 3_200_000.0, 3_800_000.0, 22_000.0, 800_000.0, 200_000.0, "NS-BARC-001", "ISDA"),
            CounterpartyProfile("CP-DB", 1_800_000.0, 2_100_000.0, 9_800.0, 400_000.0, 100_000.0, "NS-DB-001", "ISDA"),
            CounterpartyProfile("CP-UBS", 4_100_000.0, 4_600_000.0, 28_000.0, 950_000.0, 250_000.0, "NS-UBS-001", "CSA"),
            CounterpartyProfile("CP-CITI", 5_300_000.0, 5_900_000.0, 36_000.0, 1_100_000.0, 280_000.0, "NS-CITI-001", "CSA"),
        )

        private val PFE_TENORS = listOf(
            Triple("3M", 0.25, 0.9),
            Triple("6M", 0.5, 0.85),
            Triple("1Y", 1.0, 0.75),
            Triple("2Y", 2.0, 0.6),
            Triple("3Y", 3.0, 0.5),
            Triple("5Y", 5.0, 0.35),
        )

        fun buildCounterpartyExposureSnapshots(): List<CounterpartyExposureSnapshot> =
            COUNTERPARTY_PROFILES.map { profile ->
                val pfeProfile = PFE_TENORS.map { (tenor, tenorYears, decayFactor) ->
                    val ee = profile.peakPfe * decayFactor * 0.7
                    val pfe95 = profile.peakPfe * decayFactor * 0.85
                    val pfe99 = profile.peakPfe * decayFactor
                    ExposureAtTenor(tenor, tenorYears, ee, pfe95, pfe99)
                }

                CounterpartyExposureSnapshot(
                    counterpartyId = profile.counterpartyId,
                    calculatedAt = Instant.now(),
                    pfeProfile = pfeProfile,
                    currentNetExposure = profile.currentNetExposure,
                    peakPfe = profile.peakPfe,
                    cva = profile.cva,
                    cvaEstimated = false,
                    currency = "USD",
                    nettingSetExposures = listOf(
                        NettingSetExposure(
                            nettingSetId = profile.nettingSetId,
                            agreementType = profile.agreementType,
                            netExposure = profile.currentNetExposure,
                            peakPfe = profile.peakPfe,
                        ),
                    ),
                    collateralHeld = profile.collateralHeld,
                    collateralPosted = profile.collateralPosted,
                    netNetExposure = profile.currentNetExposure - profile.collateralHeld + profile.collateralPosted,
                )
            }

        /**
         * Per-book P&L attribution profiles for the Phase 3 Gap 9 demo
         * "explain" workflow. Numbers are in USD and are deliberately
         * realistic — equity books are mostly delta, derivatives books carry
         * a heavier gamma/vega story, and the residual stays below 10% of
         * total P&L. Sum of position-level attributions equals the
         * book-level totals; sum of Greek components equals total minus
         * residual. The Kotlin code does the actual reconciliation at build
         * time, so adding new books never produces a non-tie-out demo row.
         */
        private data class PositionProfile(
            val instrumentId: String,
            val assetClass: AssetClass,
            val delta: Long,
            val gamma: Long,
            val vega: Long,
            val theta: Long,
            val rho: Long,
            val vanna: Long = 0,
            val volga: Long = 0,
            val charm: Long = 0,
            val crossGamma: Long = 0,
            val unexplained: Long,
        )

        private data class BookPnlProfile(
            val bookId: String,
            val positions: List<PositionProfile>,
        )

        private val BOOK_PNL_PROFILES: List<BookPnlProfile> = listOf(
            BookPnlProfile("equity-growth", listOf(
                PositionProfile("AAPL",  AssetClass.EQUITY,     delta = 320_000, gamma = 12_000, vega = 0,      theta = -3_000,  rho = 800,   unexplained = 6_200),
                PositionProfile("MSFT",  AssetClass.EQUITY,     delta = 210_000, gamma = 8_000,  vega = 0,      theta = -1_900,  rho = 500,   unexplained = 4_400),
                PositionProfile("GOOGL", AssetClass.EQUITY,     delta = 145_000, gamma = 5_500,  vega = 0,      theta = -1_200,  rho = 300,   unexplained = 3_400),
                PositionProfile("JPM",   AssetClass.EQUITY,     delta = 88_000,  gamma = 3_100,  vega = 0,      theta = -700,    rho = 200,   unexplained = 2_400),
                PositionProfile("META",  AssetClass.EQUITY,     delta = 62_000,  gamma = 2_200,  vega = 0,      theta = -500,    rho = 100,   unexplained = 1_800),
            )),
            BookPnlProfile("tech-momentum", listOf(
                PositionProfile("NVDA",          AssetClass.EQUITY,     delta = 480_000, gamma = 18_000, vega = 0,      theta = -4_500, rho = 1_200, unexplained = 9_300),
                PositionProfile("TSLA",          AssetClass.EQUITY,     delta = 195_000, gamma = 7_400,  vega = 0,      theta = -1_700, rho = 400,   unexplained = 4_500),
                PositionProfile("AMD",           AssetClass.EQUITY,     delta = 142_000, gamma = 5_300,  vega = 0,      theta = -1_100, rho = 300,   unexplained = 3_300),
                PositionProfile("NVDA-C-950-20260620", AssetClass.DERIVATIVE, delta = 75_000,  gamma = 28_000, vega = 22_000, theta = -8_500, rho = 600,   vanna = 4_200, volga = 3_100, charm = -1_900, crossGamma = 1_400, unexplained = 5_100),
            )),
            BookPnlProfile("emerging-markets", listOf(
                PositionProfile("BABA",   AssetClass.EQUITY, delta = 165_000, gamma = 6_200, vega = 0, theta = -1_500, rho = 400, unexplained = 3_900),
                PositionProfile("EURUSD", AssetClass.FX,     delta = 92_000,  gamma = 0,     vega = 0, theta = 0,      rho = 1_100, unexplained = 2_700),
                PositionProfile("USDJPY", AssetClass.FX,     delta = 78_000,  gamma = 0,     vega = 0, theta = 0,      rho = 950,   unexplained = 2_200),
            )),
            BookPnlProfile("fixed-income", listOf(
                // Bonds are rates-driven: rho (DV01-equivalent) dominates the
                // attribution; delta here captures pure price-level sensitivity
                // independent of yield, which is modest by comparison.
                PositionProfile("US10Y", AssetClass.FIXED_INCOME, delta = -12_000, gamma = 2_100,  vega = 0,      theta = 1_200,  rho = -85_000, unexplained = 1_800),
                PositionProfile("US2Y",  AssetClass.FIXED_INCOME, delta = -5_000,  gamma = 900,    vega = 0,      theta = 600,    rho = -32_000, unexplained = 900),
                PositionProfile("US30Y", AssetClass.FIXED_INCOME, delta = -9_000,  gamma = 1_700,  vega = 0,      theta = 900,    rho = -68_000, unexplained = 1_400),
            )),
            BookPnlProfile("multi-asset", listOf(
                PositionProfile("AAPL",  AssetClass.EQUITY,     delta = 145_000, gamma = 5_400, vega = 0,      theta = -1_300, rho = 300, unexplained = 3_500),
                PositionProfile("GC",    AssetClass.COMMODITY,  delta = 68_000,  gamma = 0,     vega = 0,      theta = 0,      rho = 800, unexplained = 1_900),
                PositionProfile("US10Y", AssetClass.FIXED_INCOME, delta = -32_000, gamma = 1_500, vega = 0,      theta = 800,    rho = -19_000, unexplained = 1_200),
                PositionProfile("SPX-PUT-4500", AssetClass.DERIVATIVE, delta = -55_000, gamma = 18_500, vega = 14_000, theta = -5_200, rho = 400, vanna = 2_800, volga = 1_900, charm = -1_400, crossGamma = 900, unexplained = 3_300),
            )),
            BookPnlProfile("macro-hedge", listOf(
                PositionProfile("CL",     AssetClass.COMMODITY, delta = 92_000,  gamma = 0, vega = 0, theta = 0,    rho = 1_100, unexplained = 2_500),
                PositionProfile("GC",     AssetClass.COMMODITY, delta = 110_000, gamma = 0, vega = 0, theta = 0,    rho = 1_400, unexplained = 3_100),
                PositionProfile("EURUSD", AssetClass.FX,        delta = 55_000,  gamma = 0, vega = 0, theta = 0,    rho = 700,   unexplained = 1_500),
            )),
            BookPnlProfile("balanced-income", listOf(
                PositionProfile("JPM",    AssetClass.EQUITY,       delta = 85_000,  gamma = 3_100, vega = 0, theta = -700,  rho = 200,    unexplained = 2_100),
                PositionProfile("US30Y",  AssetClass.FIXED_INCOME, delta = -22_000, gamma = 1_100, vega = 0, theta = 600,   rho = -13_000, unexplained = 800),
                PositionProfile("KO",     AssetClass.EQUITY,       delta = 35_000,  gamma = 1_200, vega = 0, theta = -300,  rho = 100,    unexplained = 900),
            )),
            BookPnlProfile("derivatives-book", listOf(
                PositionProfile("SPX-CALL-5000",     AssetClass.DERIVATIVE, delta = 220_000, gamma = 85_000, vega = 62_000, theta = -28_000, rho = 1_900, vanna = 11_000, volga = 7_400, charm = -5_200, crossGamma = 4_100, unexplained = 14_800),
                PositionProfile("SPX-PUT-4800",      AssetClass.DERIVATIVE, delta = -180_000, gamma = 72_000, vega = 55_000, theta = -22_000, rho = -1_500, vanna = -8_400, volga = 6_100, charm = 4_100,  crossGamma = -3_200, unexplained = -11_200),
                PositionProfile("NVDA-C-950-20260620", AssetClass.DERIVATIVE, delta = 95_000,  gamma = 34_000, vega = 26_000, theta = -10_200, rho = 700,  vanna = 5_100, volga = 3_700, charm = -2_300, crossGamma = 1_700, unexplained = 6_400),
            )),
        )

        internal fun buildPnlAttributions(asOf: LocalDate): List<PnlAttribution> {
            val calculatedAt = asOf.atTime(17, 0).toInstant(ZoneOffset.UTC)
            return BOOK_PNL_PROFILES.map { profile ->
                val positionAttributions = profile.positions.map { position ->
                    val total = position.delta + position.gamma + position.vega +
                        position.theta + position.rho + position.vanna +
                        position.volga + position.charm + position.crossGamma +
                        position.unexplained
                    PositionPnlAttribution(
                        instrumentId = InstrumentId(position.instrumentId),
                        assetClass = position.assetClass,
                        totalPnl = BigDecimal(total),
                        deltaPnl = BigDecimal(position.delta),
                        gammaPnl = BigDecimal(position.gamma),
                        vegaPnl = BigDecimal(position.vega),
                        thetaPnl = BigDecimal(position.theta),
                        rhoPnl = BigDecimal(position.rho),
                        vannaPnl = BigDecimal(position.vanna),
                        volgaPnl = BigDecimal(position.volga),
                        charmPnl = BigDecimal(position.charm),
                        crossGammaPnl = BigDecimal(position.crossGamma),
                        unexplainedPnl = BigDecimal(position.unexplained),
                    )
                }
                val totalPnl = positionAttributions.sumOf { it.totalPnl }
                val deltaPnl = positionAttributions.sumOf { it.deltaPnl }
                val gammaPnl = positionAttributions.sumOf { it.gammaPnl }
                val vegaPnl = positionAttributions.sumOf { it.vegaPnl }
                val thetaPnl = positionAttributions.sumOf { it.thetaPnl }
                val rhoPnl = positionAttributions.sumOf { it.rhoPnl }
                val vannaPnl = positionAttributions.sumOf { it.vannaPnl }
                val volgaPnl = positionAttributions.sumOf { it.volgaPnl }
                val charmPnl = positionAttributions.sumOf { it.charmPnl }
                val crossGammaPnl = positionAttributions.sumOf { it.crossGammaPnl }
                val unexplainedPnl = positionAttributions.sumOf { it.unexplainedPnl }
                PnlAttribution(
                    bookId = BookId(profile.bookId),
                    date = asOf,
                    currency = "USD",
                    totalPnl = totalPnl,
                    deltaPnl = deltaPnl,
                    gammaPnl = gammaPnl,
                    vegaPnl = vegaPnl,
                    thetaPnl = thetaPnl,
                    rhoPnl = rhoPnl,
                    vannaPnl = vannaPnl,
                    volgaPnl = volgaPnl,
                    charmPnl = charmPnl,
                    crossGammaPnl = crossGammaPnl,
                    unexplainedPnl = unexplainedPnl,
                    positionAttributions = positionAttributions,
                    dataQualityFlag = AttributionDataQuality.PRICE_ONLY,
                    calculatedAt = calculatedAt,
                )
            }
        }

        fun buildSeedJobs(): List<ValuationJob> {
            val now = Instant.now()
            val today = LocalDate.now(ZoneOffset.UTC)
            val jobs = mutableListOf<ValuationJob>()

            BOOK_VAR_PROFILES.forEachIndexed { bookIdx, profile ->
                // Daily entries for the past 30 days (one per day at 09:30 UTC)
                for (dayOffset in HISTORY_DAYS downTo 2) {
                    val date = today.minusDays(dayOffset.toLong())
                    val startedAt = date.atTime(9, 30).toInstant(ZoneOffset.UTC)
                    val jitter = deterministicJitter(bookIdx, dayOffset, 0)
                    val varValue = profile.baseVaR * (1.0 + jitter * profile.volatilityPct)
                    val esValue = profile.baseES * (1.0 + jitter * profile.volatilityPct)

                    jobs += ValuationJob(
                        jobId = UUID.nameUUIDFromBytes("seed-var-${profile.bookId}-d$dayOffset".toByteArray()),
                        bookId = profile.bookId,
                        triggerType = TriggerType.SCHEDULED,
                        status = RunStatus.COMPLETED,
                        startedAt = startedAt,
                        valuationDate = date,
                        completedAt = startedAt.plusMillis(1500),
                        durationMs = 1500,
                        calculationType = "PARAMETRIC",
                        confidenceLevel = "CL_95",
                        varValue = varValue,
                        expectedShortfall = esValue,
                        triggeredBy = "SEED",
                    )
                }

                // Intraday entries for today (hourly from market open)
                for (hour in 0 until INTRADAY_HOURS) {
                    val startedAt = now.minus((INTRADAY_HOURS - hour).toLong(), ChronoUnit.HOURS)
                    val jitter = deterministicJitter(bookIdx, 0, hour)
                    val varValue = profile.baseVaR * (1.0 + jitter * profile.volatilityPct)
                    val esValue = profile.baseES * (1.0 + jitter * profile.volatilityPct)

                    jobs += ValuationJob(
                        jobId = UUID.nameUUIDFromBytes("seed-var-${profile.bookId}-h$hour".toByteArray()),
                        bookId = profile.bookId,
                        triggerType = TriggerType.SCHEDULED,
                        status = RunStatus.COMPLETED,
                        startedAt = startedAt,
                        valuationDate = today,
                        completedAt = startedAt.plusMillis(1800),
                        durationMs = 1800,
                        calculationType = "PARAMETRIC",
                        confidenceLevel = "CL_95",
                        varValue = varValue,
                        expectedShortfall = esValue,
                        triggeredBy = "SEED",
                    )
                }
            }

            return jobs
        }
    }
}
