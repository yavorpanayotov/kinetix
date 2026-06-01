package com.kinetix.risk.seed

import com.kinetix.risk.model.CounterpartyExposureSnapshot
import com.kinetix.risk.model.ExposureAtTenor
import com.kinetix.risk.model.NettingSetExposure
import com.kinetix.risk.model.RunStatus
import com.kinetix.risk.model.TriggerType
import com.kinetix.risk.model.ValuationJob
import com.kinetix.risk.persistence.CounterpartyExposureRepository
import com.kinetix.risk.persistence.PnlAttributionRepository
import com.kinetix.risk.service.ValuationJobRecorder
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
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
    private val pnLAttributionDeriver: PnLAttributionDeriver = PnLAttributionDeriver(),
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
            // OfficialEodDesignations has no FK cascade from valuation_jobs, so
            // designations pointing at the deleted SEED jobs would be orphaned.
            // Purge them before promoting the fresh batch.
            val purged = jobRecorder.deleteOfficialEodDesignationsByPromotedBy("SEED")
            if (purged > 0) {
                log.info("Deleted {} stale SEED EOD designations before re-seeding", purged)
            }
        }

        val jobs = buildSeedJobs()
        log.info("Seeding {} VaR timeline entries across {} books", jobs.size, BOOK_VAR_PROFILES.size)

        val today = LocalDate.now(ZoneOffset.UTC)
        var promoted = 0
        for (job in jobs) {
            jobRecorder.save(job)
            // Promote historical weekday jobs to OFFICIAL_EOD so the EOD
            // timeline view has data immediately after deploy. Today is owned
            // by ScheduledAutoCloseJob (17:30 UTC), weekends are skipped to
            // match production semantics.
            if (job.valuationDate < today && job.valuationDate.isWeekday()) {
                jobRecorder.promoteToOfficialEod(
                    jobId = job.jobId,
                    promotedBy = "SEED",
                    promotedAt = job.completedAt ?: job.startedAt,
                )
                promoted++
            }
        }

        log.info("VaR timeline seeding complete — promoted {} historical weekday jobs to OFFICIAL_EOD", promoted)
    }

    private fun LocalDate.isWeekday(): Boolean =
        dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY

    /**
     * Phase 3 — derive per-book daily P&L attribution from positions × tape moves.
     *
     * For each book in [DemoBookCatalogue], walks all 252 days of the deterministic
     * [DemoTape] and emits a daily attribution row: delta/gamma/vega/theta/rho/residual
     * decomposition of total P&L. Cash positions drive pure delta; option positions use
     * closed-form Black-Scholes Greeks from `common.demo.BlackScholes`. Stress-window
     * drawdowns fall out for free — the tape's STRESS_2020/STRESS_2022 regimes push
     * large negative price moves through the delta term, so daily P&L during the stress
     * windows is materially worse than calm windows by construction.
     *
     * The persistence layer upserts on (bookId, attributionDate), so re-running the
     * seeder against an already-populated table is safe and idempotent.
     */
    private suspend fun seedPnlAttribution() {
        val repo = pnlAttributionRepository ?: return

        val attributions = pnLAttributionDeriver.derive()
        log.info("Seeding {} P&L attribution rows across {} books",
            attributions.size, attributions.map { it.bookId.value }.distinct().size)

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
            /**
             * Baseline portfolio value used to populate `valuation_jobs.pv_value`
             * on every SEED job. Picked at ~30x baseVaR so the VaR/PV ratio sits
             * in the 2-4% band typical for a real institutional book — keeps the
             * EOD History "PV" column meaningful for risk-vs-PV reconciliation
             * (kx-pv-eod).
             */
            val basePv: Double,
        )

        private val BOOK_VAR_PROFILES = listOf(
            VaRProfile("equity-growth", 4_800_000.0, 0.08, 5_900_000.0, 144_000_000.0),
            VaRProfile("tech-momentum", 4_200_000.0, 0.10, 5_100_000.0, 126_000_000.0),
            VaRProfile("emerging-markets", 3_600_000.0, 0.12, 4_400_000.0, 108_000_000.0),
            VaRProfile("fixed-income", 1_800_000.0, 0.04, 2_200_000.0, 54_000_000.0),
            VaRProfile("multi-asset", 6_500_000.0, 0.07, 7_900_000.0, 195_000_000.0),
            VaRProfile("macro-hedge", 4_100_000.0, 0.09, 5_000_000.0, 123_000_000.0),
            VaRProfile("balanced-income", 2_200_000.0, 0.05, 2_700_000.0, 66_000_000.0),
            VaRProfile("derivatives-book", 5_800_000.0, 0.11, 7_100_000.0, 174_000_000.0),
        )

        private const val HISTORY_DAYS = 30
        private const val INTRADAY_HOURS = 8

        /**
         * Smaller-than-VaR daily PV drift — a real book's NAV moves O(0.5-1%)
         * day-to-day, not the O(8-12%) swings the VaR profiles dial in. Keeps
         * PV visibly stable in the EOD History grid (kx-pv-eod).
         */
        private const val PV_VOLATILITY_PCT = 0.01

        private fun deterministicJitter(bookIndex: Int, dayIndex: Int, hourIndex: Int): Double {
            val seed = bookIndex * 1000.0 + dayIndex * 10.0 + hourIndex
            return sin(seed * 0.7) * 0.5 + sin(seed * 1.3) * 0.3 + sin(seed * 2.1) * 0.2
        }

        /**
         * Demo counterparty profile carrying both the exposure inputs (used by
         * the existing PFE / netting-set / collateral fixture builder) and the
         * demo-facing identity fields requested by kx-i72: a friendly [name]
         * and a [creditRating]. The identity fields are exposed via
         * [DemoCounterparties] so the simulated trader and any UI tile that
         * lists counterparties can render a human-readable name without a
         * round-trip to reference-data-service.
         */
        private data class CounterpartyProfile(
            val counterpartyId: String,
            val name: String,
            val creditRating: String,
            val currentNetExposure: Double,
            val peakPfe: Double,
            val cva: Double?,
            val collateralHeld: Double,
            val collateralPosted: Double,
            val nettingSetId: String,
            val agreementType: String,
        )

        // Exposure profiles for every demo counterparty in
        // `common.demo.CounterpartyTiers.ALL_IDS`. Originally only the 6 G-SIBs
        // carried exposure, leaving the other 24 reference-data counterparties
        // (mid-tier banks, CCPs, buy-side, corporates) showing $0.00 in the
        // Counterparty Risk tab (kx-8kqk). Each tier gets size-appropriate,
        // deliberately non-uniform numbers:
        //   - G-SIBs            largest gross exposure, ISDA/CSA
        //   - mid-tier banks    moderate, CSA-collateralised
        //   - CCPs              cleared → heavily margined, negligible CVA (null)
        //   - buy-side funds    moderate, CSA-collateralised
        //   - corporates        smaller, often uncollateralised (higher CVA/exposure ratio)
        // The friendly name + S&P-style rating drive the UI tile (kx-i72).
        private val COUNTERPARTY_PROFILES = listOf(
            // G-SIBs
            CounterpartyProfile("CP-GS", "Goldman Sachs", "A+", 2_000_000.0, 1_800_000.0, 12_500.0, 500_000.0, 150_000.0, "NS-GS-001", "ISDA"),
            CounterpartyProfile("CP-JPM", "JPMorgan Chase", "AA-", 6_500_000.0, 7_200_000.0, 45_000.0, 1_200_000.0, 300_000.0, "NS-JPM-001", "ISDA"),
            CounterpartyProfile("CP-BARC", "Barclays", "A", 3_200_000.0, 3_800_000.0, 22_000.0, 800_000.0, 200_000.0, "NS-BARC-001", "ISDA"),
            CounterpartyProfile("CP-DB", "Deutsche Bank", "BBB+", 1_800_000.0, 2_100_000.0, 9_800.0, 400_000.0, 100_000.0, "NS-DB-001", "ISDA"),
            CounterpartyProfile("CP-UBS", "UBS", "A+", 4_100_000.0, 4_600_000.0, 28_000.0, 950_000.0, 250_000.0, "NS-UBS-001", "CSA"),
            CounterpartyProfile("CP-CITI", "Citigroup", "A", 5_300_000.0, 5_900_000.0, 36_000.0, 1_100_000.0, 280_000.0, "NS-CITI-001", "CSA"),
            // Mid-tier banks
            CounterpartyProfile("CP-WFC", "Wells Fargo", "A+", 2_400_000.0, 2_650_000.0, 16_400.0, 620_000.0, 170_000.0, "NS-WFC-001", "CSA"),
            CounterpartyProfile("CP-BNP", "BNP Paribas", "A+", 2_900_000.0, 3_150_000.0, 18_700.0, 710_000.0, 190_000.0, "NS-BNP-001", "CSA"),
            CounterpartyProfile("CP-SOCG", "Société Générale", "A", 1_650_000.0, 1_900_000.0, 13_200.0, 430_000.0, 110_000.0, "NS-SOCG-001", "CSA"),
            CounterpartyProfile("CP-MIZ", "Mizuho", "A-", 1_200_000.0, 1_420_000.0, 11_900.0, 320_000.0, 80_000.0, "NS-MIZ-001", "CSA"),
            CounterpartyProfile("CP-NMR", "Nomura", "A-", 980_000.0, 1_180_000.0, 12_600.0, 260_000.0, 70_000.0, "NS-NMR-001", "CSA"),
            CounterpartyProfile("CP-RBC", "Royal Bank of Canada", "AA-", 2_150_000.0, 2_350_000.0, 9_400.0, 580_000.0, 160_000.0, "NS-RBC-001", "CSA"),
            CounterpartyProfile("CP-ING", "ING Group", "A+", 1_480_000.0, 1_690_000.0, 11_100.0, 390_000.0, 100_000.0, "NS-ING-001", "CSA"),
            CounterpartyProfile("CP-SAN", "Santander", "A", 1_320_000.0, 1_560_000.0, 12_900.0, 350_000.0, 90_000.0, "NS-SAN-001", "CSA"),
            CounterpartyProfile("CP-HAND", "Handelsbanken", "AA-", 870_000.0, 1_020_000.0, 6_300.0, 240_000.0, 60_000.0, "NS-HAND-001", "CSA"),
            CounterpartyProfile("CP-BBVA", "BBVA", "A", 1_110_000.0, 1_330_000.0, 11_700.0, 300_000.0, 80_000.0, "NS-BBVA-001", "CSA"),
            // CCPs — centrally cleared, heavily margined, negligible counterparty CVA
            CounterpartyProfile("CP-LCH", "LCH Clearnet", "AA-", 740_000.0, 920_000.0, null, 1_400_000.0, 1_350_000.0, "NS-LCH-001", "CSA"),
            CounterpartyProfile("CP-CME", "CME Clearing", "AA", 1_050_000.0, 1_280_000.0, null, 1_800_000.0, 1_720_000.0, "NS-CME-001", "CSA"),
            CounterpartyProfile("CP-EUREX", "Eurex Clearing", "AA", 690_000.0, 850_000.0, null, 1_250_000.0, 1_210_000.0, "NS-EUREX-001", "CSA"),
            CounterpartyProfile("CP-ICE", "ICE Clear", "AA-", 820_000.0, 990_000.0, null, 1_450_000.0, 1_400_000.0, "NS-ICE-001", "CSA"),
            // Buy-side funds
            CounterpartyProfile("CP-BLK", "BlackRock", "AA-", 1_240_000.0, 1_460_000.0, 14_200.0, 330_000.0, 90_000.0, "NS-BLK-001", "CSA"),
            CounterpartyProfile("CP-BRDG", "Bridgewater Associates", "A", 760_000.0, 910_000.0, 12_800.0, 200_000.0, 50_000.0, "NS-BRDG-001", "CSA"),
            CounterpartyProfile("CP-CITDL", "Citadel", "A-", 1_080_000.0, 1_290_000.0, 17_500.0, 280_000.0, 70_000.0, "NS-CITDL-001", "CSA"),
            CounterpartyProfile("CP-MIL", "Millennium", "A-", 640_000.0, 780_000.0, 13_900.0, 170_000.0, 40_000.0, "NS-MIL-001", "CSA"),
            // Corporates — typically uncollateralised, higher CVA per unit exposure
            CounterpartyProfile("CP-AAPL", "Apple Inc.", "AA+", 540_000.0, 690_000.0, 8_900.0, 0.0, 0.0, "NS-AAPL-001", "ISDA"),
            CounterpartyProfile("CP-SHEL", "Shell plc", "A+", 720_000.0, 880_000.0, 15_300.0, 80_000.0, 0.0, "NS-SHEL-001", "ISDA"),
            CounterpartyProfile("CP-TM", "Toyota Motor", "A+", 610_000.0, 760_000.0, 12_100.0, 60_000.0, 0.0, "NS-TM-001", "ISDA"),
            CounterpartyProfile("CP-NESN", "Nestlé", "AA-", 470_000.0, 590_000.0, 7_200.0, 0.0, 0.0, "NS-NESN-001", "ISDA"),
            CounterpartyProfile("CP-MSFT", "Microsoft", "AAA", 580_000.0, 720_000.0, 5_400.0, 0.0, 0.0, "NS-MSFT-001", "ISDA"),
            CounterpartyProfile("CP-BA", "Boeing", "BBB-", 890_000.0, 1_080_000.0, 31_600.0, 120_000.0, 0.0, "NS-BA-001", "ISDA"),
        )

        /**
         * Demo counterparty identity tuple for the UI and the simulated
         * trader. Distinct from [CounterpartyExposureSnapshot] which carries
         * a heavier payload (PFE / CVA / netting). Exposed so other modules
         * can list "the 6 demo counterparties" without re-deriving the set.
         */
        data class DemoCounterparty(
            val id: String,
            val name: String,
            val creditRating: String,
        )

        /** The 6 demo counterparties — id, friendly name, and credit rating (kx-i72). */
        val DEMO_COUNTERPARTIES: List<DemoCounterparty> = COUNTERPARTY_PROFILES.map {
            DemoCounterparty(it.counterpartyId, it.name, it.creditRating)
        }

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

        fun buildSeedJobs(): List<ValuationJob> {
            val now = Instant.now()
            val today = LocalDate.now(ZoneOffset.UTC)
            val jobs = mutableListOf<ValuationJob>()

            BOOK_VAR_PROFILES.forEachIndexed { bookIdx, profile ->
                // Daily entries for the past 30 days (one per day at 09:30 UTC).
                // Range is T-30..T-1 so yesterday is covered; today (T) is
                // owned by ScheduledAutoCloseJob at 17:30 UTC.
                for (dayOffset in HISTORY_DAYS downTo 1) {
                    val date = today.minusDays(dayOffset.toLong())
                    val startedAt = date.atTime(9, 30).toInstant(ZoneOffset.UTC)
                    val jitter = deterministicJitter(bookIdx, dayOffset, 0)
                    val varValue = profile.baseVaR * (1.0 + jitter * profile.volatilityPct)
                    val esValue = profile.baseES * (1.0 + jitter * profile.volatilityPct)
                    // PV drifts with the same deterministic jitter but at a
                    // smaller amplitude — a real book's NAV moves day-to-day
                    // far less than its VaR (kx-pv-eod).
                    val pvValue = profile.basePv * (1.0 + jitter * PV_VOLATILITY_PCT)

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
                        pvValue = pvValue,
                        triggeredBy = "SEED",
                    )
                }

                // Intraday entries for today (hourly from market open)
                for (hour in 0 until INTRADAY_HOURS) {
                    val startedAt = now.minus((INTRADAY_HOURS - hour).toLong(), ChronoUnit.HOURS)
                    val jitter = deterministicJitter(bookIdx, 0, hour)
                    val varValue = profile.baseVaR * (1.0 + jitter * profile.volatilityPct)
                    val esValue = profile.baseES * (1.0 + jitter * profile.volatilityPct)
                    val pvValue = profile.basePv * (1.0 + jitter * PV_VOLATILITY_PCT)

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
                        pvValue = pvValue,
                        triggeredBy = "SEED",
                    )
                }
            }

            return jobs
        }
    }
}
