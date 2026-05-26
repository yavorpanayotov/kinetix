package com.kinetix.risk.service

import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.PricingGreeksClient
import com.kinetix.risk.client.PricingGreeksInstrumentInput
import com.kinetix.risk.client.RatesServiceClient
import com.kinetix.risk.client.VolatilityServiceClient
import com.kinetix.risk.model.*
import com.kinetix.risk.persistence.DailyRiskSnapshotRepository
import com.kinetix.risk.persistence.SodBaselineRepository
import com.kinetix.risk.persistence.SodGreekSnapshotRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

class SodSnapshotService(
    private val sodBaselineRepository: SodBaselineRepository,
    private val dailyRiskSnapshotRepository: DailyRiskSnapshotRepository,
    private val varCache: VaRCache,
    private val varCalculationService: VaRCalculationService,
    private val positionProvider: PositionProvider,
    private val jobRecorder: ValuationJobRecorder? = null,
    private val maxCacheAgeMinutes: Long = 120,
    private val volatilityServiceClient: VolatilityServiceClient? = null,
    private val ratesServiceClient: RatesServiceClient? = null,
    /**
     * Optional analytical-pricing-Greek source (per ADR-0032). When wired, the SOD job
     * computes closed-form pricing Greeks alongside the VaR snapshot and persists them
     * to [sodGreekSnapshotRepository]. If either dependency is null the SOD job still
     * succeeds but consumers (IntradayPnlService, PnlComputationService) fall back to
     * VaR Greeks. Both should be wired together.
     */
    private val pricingGreeksClient: PricingGreeksClient? = null,
    private val sodGreekSnapshotRepository: SodGreekSnapshotRepository? = null,
) {
    private val logger = LoggerFactory.getLogger(SodSnapshotService::class.java)

    suspend fun createSnapshot(
        bookId: BookId,
        snapshotType: SnapshotType,
        valuationResult: ValuationResult? = null,
        date: LocalDate = LocalDate.now(),
    ) {
        val result = valuationResult
            ?: varCache.get(bookId.value)?.takeIf { isFreshEnough(it) }
            ?: calculateFreshVaR(bookId)
            ?: throw IllegalStateException("Cannot create SOD snapshot: no valuation data available for ${bookId.value}")

        val positions = positionProvider.getPositions(bookId)

        val snapshots = result.positionRisk.map { risk ->
            val position = positions.find { it.instrumentId == risk.instrumentId }
            val sodVol = fetchSodVol(risk.instrumentId)
            val currency = position?.currency ?: Currency.getInstance("USD")
            val sodRate = fetchSodRate(currency)
            DailyRiskSnapshot(
                bookId = bookId,
                snapshotDate = date,
                instrumentId = risk.instrumentId,
                assetClass = risk.assetClass,
                quantity = position?.quantity ?: BigDecimal.ONE,
                marketPrice = position?.marketPrice?.amount ?: risk.marketValue,
                delta = risk.delta,
                gamma = risk.gamma,
                vega = risk.vega,
                theta = null,
                rho = null,
                sodVol = sodVol,
                sodRate = sodRate,
            )
        }

        dailyRiskSnapshotRepository.saveAll(snapshots)

        // Capture analytical pricing Greeks alongside the VaR snapshot. Errors here
        // never fail the SOD job — consumers fall back to VaR Greeks if the pricing
        // table is empty for the day. This matches the audit A-3 Phase 1 wiring.
        persistPricingGreeks(bookId, date, positions, snapshots)

        val baseline = SodBaseline(
            bookId = bookId,
            baselineDate = date,
            snapshotType = snapshotType,
            createdAt = Instant.now(),
            sourceJobId = result.jobId,
            calculationType = result.calculationType.name,
            varValue = result.varValue,
            expectedShortfall = result.expectedShortfall,
        )
        sodBaselineRepository.save(baseline)

        logger.info(
            "SOD snapshot created for portfolio {} on {} ({}, {} positions)",
            bookId.value, date, snapshotType, snapshots.size,
        )
    }

    /**
     * Computes analytical pricing Greeks via the engine and persists them. Best-effort:
     * any failure is logged and swallowed so the SOD job still produces the VaR snapshot
     * and baseline. Consumers (IntradayPnlService, PnlComputationService) tolerate empty
     * pricing-Greek rows by falling back to VaR Greeks per-instrument.
     */
    private suspend fun persistPricingGreeks(
        bookId: BookId,
        date: LocalDate,
        positions: List<com.kinetix.common.model.Position>,
        snapshots: List<DailyRiskSnapshot>,
    ) {
        val client = pricingGreeksClient ?: return
        val repo = sodGreekSnapshotRepository ?: return

        val inputs = positions.mapNotNull { pos ->
            val sod = snapshots.firstOrNull { it.instrumentId == pos.instrumentId } ?: return@mapNotNull null
            buildPricingGreeksInput(pos, sod)
        }
        if (inputs.isEmpty()) return

        try {
            val results = client.calculatePricingGreeks(inputs)
            if (results.isEmpty()) return

            val byInstrument = positions.associateBy { it.instrumentId }
            val now = Instant.now()
            val rows = results.map { r ->
                val pos = byInstrument[com.kinetix.common.model.InstrumentId(r.instrumentId)]
                SodGreekSnapshot(
                    bookId = bookId,
                    snapshotDate = date,
                    instrumentId = com.kinetix.common.model.InstrumentId(r.instrumentId),
                    sodPrice = pos?.marketPrice?.amount ?: BigDecimal.ZERO,
                    sodVol = snapshots.firstOrNull { it.instrumentId.value == r.instrumentId }?.sodVol,
                    sodRate = snapshots.firstOrNull { it.instrumentId.value == r.instrumentId }?.sodRate,
                    delta = r.delta.takeIf { it != 0.0 },
                    gamma = r.gamma.takeIf { it != 0.0 },
                    vega = r.vega.takeIf { it != 0.0 },
                    theta = r.theta.takeIf { it != 0.0 },
                    rho = r.rho.takeIf { it != 0.0 },
                    vanna = r.vanna.takeIf { it != 0.0 },
                    volga = r.volga.takeIf { it != 0.0 },
                    charm = r.charm.takeIf { it != 0.0 },
                    bondDv01 = r.bondDv01.takeIf { it != 0.0 },
                    swapDv01 = r.swapDv01.takeIf { it != 0.0 },
                    createdAt = now,
                )
            }
            repo.saveAll(rows)
            logger.info(
                "SOD pricing Greeks persisted for portfolio {} on {} ({} of {} instruments priced)",
                bookId.value, date, rows.size, inputs.size,
            )
        } catch (e: Exception) {
            logger.warn(
                "SOD pricing-Greek calculation failed for portfolio {} on {}: {} — consumers will fall back to VaR Greeks",
                bookId.value, date, e.message,
            )
        }
    }

    /**
     * Builds a pricing-Greek input for a single position. Returns null when we don't have
     * enough information to price the instrument analytically — those will be silently
     * skipped and the consumer will fall back to VaR Greeks.
     *
     * Asset-class dispatch is conservative: only the instruments we know how to price end
     * up in the request. Options need strike/expiry/vol metadata which positions don't
     * carry today, so they're routed as EQUITY (identity delta) rather than OPTION until
     * the position model carries the option-specific fields. This is acceptable interim
     * behaviour — option attribution then uses VaR Greeks via the per-instrument fallback.
     */
    private fun buildPricingGreeksInput(
        position: com.kinetix.common.model.Position,
        snapshot: DailyRiskSnapshot,
    ): PricingGreeksInstrumentInput? {
        val ac = position.assetClass.name
        val spot = position.marketPrice.amount.toDouble()
        return when (ac) {
            "EQUITY", "FX" -> PricingGreeksInstrumentInput(
                instrumentId = position.instrumentId.value,
                assetClass = ac,
                spotPrice = spot,
            )
            // Other asset classes are skipped until the position model carries the
            // closed-form-pricing inputs (strike/vol for options, face/coupon/maturity
            // for bonds). Their consumers fall back to VaR Greeks per-instrument.
            else -> null
        }
    }

    suspend fun getBaselineStatus(bookId: BookId, date: LocalDate): SodBaselineStatus {
        val baseline = sodBaselineRepository.findByBookIdAndDate(bookId, date)
        return if (baseline != null) {
            SodBaselineStatus(
                exists = true,
                baselineDate = baseline.baselineDate.toString(),
                snapshotType = baseline.snapshotType,
                createdAt = baseline.createdAt,
                sourceJobId = baseline.sourceJobId?.toString(),
                calculationType = baseline.calculationType,
            )
        } else {
            SodBaselineStatus(exists = false)
        }
    }

    suspend fun createSnapshotFromJob(
        bookId: BookId,
        jobId: UUID,
        date: LocalDate = LocalDate.now(),
    ) {
        val recorder = jobRecorder
            ?: throw IllegalStateException("Job recorder is not configured")
        val job = recorder.findByJobId(jobId)
            ?: throw IllegalArgumentException("Valuation job $jobId not found")
        require(job.status == RunStatus.COMPLETED) {
            "Valuation job $jobId is not completed (status: ${job.status})"
        }
        require(job.bookId == bookId.value) {
            "Valuation job $jobId belongs to portfolio ${job.bookId}, not ${bookId.value}"
        }

        val calcType = CalculationType.valueOf(job.calculationType ?: "PARAMETRIC")
        val confLevel = ConfidenceLevel.valueOf(job.confidenceLevel ?: "CL_95")
        val request = VaRCalculationRequest(
            bookId = bookId,
            calculationType = calcType,
            confidenceLevel = confLevel,
            requestedOutputs = ValuationOutput.entries.toSet(),
        )
        val result = varCalculationService.calculateVaR(request, TriggerType.SCHEDULED, triggeredBy = "SYSTEM")
            ?: throw IllegalStateException("Re-calculation failed for job $jobId parameters")

        createSnapshot(bookId, SnapshotType.MANUAL, result, date)
    }

    suspend fun resetBaseline(bookId: BookId, date: LocalDate) {
        dailyRiskSnapshotRepository.deleteByBookIdAndDate(bookId, date)
        sodBaselineRepository.deleteByBookIdAndDate(bookId, date)
        logger.info("SOD baseline reset for portfolio {} on {}", bookId.value, date)
    }

    private suspend fun calculateFreshVaR(bookId: BookId): ValuationResult? {
        logger.info("No cached VaR for {}, triggering fresh calculation for SOD snapshot", bookId.value)
        val request = VaRCalculationRequest(
            bookId = bookId,
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
            requestedOutputs = ValuationOutput.entries.toSet(),
        )
        val result = varCalculationService.calculateVaR(request, TriggerType.SCHEDULED, runLabel = RunLabel.SOD, triggeredBy = "SYSTEM")
        // Write back so downstream readers (HierarchyRiskService, limit checks) see a
        // populated cache after SOD. In demo mode this is the main daily refresh; without
        // it the cache stays cold from SOD all the way to the next EOD promotion.
        if (result != null) {
            varCache.put(bookId.value, result)
        }
        return result
    }

    private fun isFreshEnough(result: ValuationResult): Boolean {
        val age = java.time.Duration.between(result.calculatedAt, Instant.now())
        val fresh = age.toMinutes() <= maxCacheAgeMinutes
        if (!fresh) {
            logger.info("Cached VaR is {}min old (max {}min), will recalculate", age.toMinutes(), maxCacheAgeMinutes)
        }
        return fresh
    }

    /**
     * Fetches the current ATM implied vol (1-month tenor) for [instrumentId] from the vol service.
     * Uses the vol surface's ATM point: strike=marketPrice with maturity=30 days.
     * Returns null and logs a warning on failure — callers treat null as zero volChange.
     */
    private suspend fun fetchSodVol(instrumentId: InstrumentId): Double? {
        val client = volatilityServiceClient ?: return null
        return when (val response = client.getLatestSurface(instrumentId)) {
            is ClientResponse.Success -> {
                val surface = response.value
                // Use the first point as an ATM proxy; the surface may be flat for non-option instruments.
                surface.points.firstOrNull()?.impliedVol?.toDouble()
            }
            else -> {
                logger.debug("No vol surface for {} at SOD — sodVol will be null", instrumentId.value)
                null
            }
        }
    }

    /**
     * Fetches the current 1Y risk-free rate for [currency] from the rates service.
     * Returns null and logs a warning on failure — callers treat null as zero rateChange.
     */
    private suspend fun fetchSodRate(currency: Currency): Double? {
        val client = ratesServiceClient ?: return null
        return when (val response = client.getLatestRiskFreeRate(currency, "1Y")) {
            is ClientResponse.Success -> response.value.rate
            else -> {
                logger.debug("No risk-free rate for {} 1Y at SOD — sodRate will be null", currency.currencyCode)
                null
            }
        }
    }
}
