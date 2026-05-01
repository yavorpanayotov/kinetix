package com.kinetix.risk.service

import com.kinetix.risk.client.CVAResult
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.CounterpartyRiskClient
import com.kinetix.risk.client.PFEPositionInput
import com.kinetix.risk.client.PFEResult
import com.kinetix.risk.client.PositionServiceClient
import com.kinetix.risk.client.ReferenceDataServiceClient
import com.kinetix.risk.client.dtos.CounterpartyDto
import com.kinetix.risk.client.dtos.NettingAgreementDto
import com.kinetix.risk.model.CounterpartyExposureSnapshot
import com.kinetix.risk.model.ExposureAtTenor
import com.kinetix.risk.model.NettingSetExposure
import com.kinetix.risk.persistence.CounterpartyExposureRepository
import org.slf4j.LoggerFactory
import java.time.Instant

private data class NettingSetPfeResult(
    val nettingSetId: String,
    val agreementType: String,
    val pfeResult: PFEResult,
)

class CounterpartyRiskOrchestrationService(
    private val referenceDataClient: ReferenceDataServiceClient,
    private val counterpartyRiskClient: CounterpartyRiskClient,
    private val positionServiceClient: PositionServiceClient,
    private val repository: CounterpartyExposureRepository,
) {
    private val logger = LoggerFactory.getLogger(CounterpartyRiskOrchestrationService::class.java)

    /**
     * Fetches netting set data for the counterparty, runs PFE via Monte Carlo for each
     * netting set, and persists the combined snapshot.
     *
     * Positions are grouped by their netting-set assignment (fetched from position-service).
     * Positions with no assignment fall into a synthetic "UNASSIGNED" group and are run
     * as a standalone PFE calculation with no CSA netting benefit.
     *
     * Aggregation rules:
     *   - totalNetExposure = sum of max(0, netExposure) across all netting sets
     *   - peakPfe = max pfe95 across all netting sets
     *   - pfeProfile = tenors from the first netting set (representative for CVA)
     */
    suspend fun computeAndPersistPFE(
        counterpartyId: String,
        positions: List<PFEPositionInput>,
        numSimulations: Int = 0,
        seed: Long = 0L,
    ): CounterpartyExposureSnapshot {
        val counterparty = when (val resp = referenceDataClient.getCounterparty(counterpartyId)) {
            is ClientResponse.Success -> resp.value
            else -> throw IllegalArgumentException("Counterparty not found: $counterpartyId")
        }

        val nettingAgreements = when (val resp = referenceDataClient.getNettingAgreements(counterpartyId)) {
            is ClientResponse.Success -> resp.value
            else -> emptyList()
        }

        // Fetch instrument -> nettingSetId mapping from position-service.
        // On failure, fall back to treating all positions as unassigned.
        val instrumentNettingSets: Map<String, String> = when (
            val resp = try {
                positionServiceClient.getInstrumentNettingSets(counterpartyId)
            } catch (e: Exception) {
                logger.warn(
                    "Instrument netting-set lookup failed for counterparty {}: {}",
                    counterpartyId,
                    e.message,
                )
                ClientResponse.NotFound(503)
            }
        ) {
            is ClientResponse.Success -> resp.value
            else -> emptyMap()
        }

        // Build agreementType lookup by nettingSetId so each PFE call uses the correct agreement type.
        val agreementTypeBySet: Map<String, String> =
            nettingAgreements.associate { it.nettingSetId to it.agreementType }
        val primaryAgreement = nettingAgreements.firstOrNull()

        // Group positions by netting set.  Positions with no assignment go to UNASSIGNED.
        val UNASSIGNED = "$counterpartyId-UNASSIGNED"
        val positionsByNettingSet: Map<String, List<PFEPositionInput>> = positions
            .groupBy { pos -> instrumentNettingSets[pos.instrumentId] ?: UNASSIGNED }

        // If there are no positions at all, run a single PFE with the primary netting set
        // so downstream CVA and collateral logic still produces a coherent result.
        val groups: Map<String, List<PFEPositionInput>> = if (positionsByNettingSet.isEmpty()) {
            val fallbackSetId = primaryAgreement?.nettingSetId ?: "$counterpartyId-DEFAULT"
            mapOf(fallbackSetId to emptyList())
        } else {
            positionsByNettingSet
        }

        // Run PFE once for each netting set (sequential — each call is independent).
        val setResults = mutableListOf<NettingSetPfeResult>()
        for ((setId, setPositions) in groups) {
            val agreementType = agreementTypeBySet[setId] ?: "NONE"
            val pfeResult = counterpartyRiskClient.calculatePFE(
                counterpartyId = counterpartyId,
                nettingSetId = setId,
                agreementType = agreementType,
                positions = setPositions,
                numSimulations = numSimulations,
                seed = seed,
            )
            setResults += NettingSetPfeResult(setId, agreementType, pfeResult)
        }

        // Aggregate: total net exposure = sum of per-set max(0, netExposure).
        val totalNetExposure = setResults.sumOf { maxOf(0.0, it.pfeResult.netExposure) }
        val peakPfe = setResults
            .flatMap { it.pfeResult.exposureProfile }
            .maxOfOrNull { it.pfe95 } ?: 0.0

        // Use the first set's exposure profile for CVA (most representative).
        val representativeProfile = setResults.firstOrNull()?.pfeResult?.exposureProfile ?: emptyList()

        val nettingSetExposures = setResults.map { sr ->
            val setPeakPfe = if (sr.pfeResult.exposureProfile.isEmpty()) 0.0
            else sr.pfeResult.exposureProfile.maxOf { it.pfe95 }
            NettingSetExposure(
                nettingSetId = sr.nettingSetId,
                agreementType = sr.agreementType,
                netExposure = maxOf(0.0, sr.pfeResult.netExposure),
                peakPfe = setPeakPfe,
            )
        }

        // CVA requires credit data (pd1y or cdsSpreadBps). Without it the result would be
        // a misleading zero rather than a meaningful figure, so we skip the calculation
        // and leave cva null to signal "unknown" rather than "zero risk".
        val cvaResult = if (!hasCreditData(counterparty)) {
            logger.warn(
                "Skipping CVA for counterparty {} — no credit data (pd1y and cdsSpreadBps are both absent)",
                counterpartyId,
            )
            null
        } else {
            try {
                counterpartyRiskClient.calculateCVA(
                    counterpartyId = counterpartyId,
                    exposureProfile = representativeProfile,
                    lgd = counterparty.lgd,
                    pd1y = counterparty.pd1y ?: 0.0,
                    cdsSpreadBps = counterparty.cdsSpreadBps ?: 0.0,
                    rating = counterparty.ratingSp ?: "",
                    sector = counterparty.sector,
                    riskFreeRate = 0.0,
                )
            } catch (e: Exception) {
                logger.warn("CVA calculation failed for {}: {}", counterpartyId, e.message)
                null
            }
        }

        // Collateral: fetch real balances from position-service.
        // Falls back to CSA threshold approximation when position-service is unreachable.
        val collateralFetch = try {
            positionServiceClient.getNetCollateral(counterpartyId)
        } catch (e: Exception) {
            logger.warn("Collateral fetch failed for {}, falling back to CSA threshold: {}", counterpartyId, e.message)
            ClientResponse.NotFound(503)
        }

        val collateralHeld: Double
        val collateralPosted: Double
        when (collateralFetch) {
            is ClientResponse.Success -> {
                collateralHeld = collateralFetch.value.collateralReceived
                collateralPosted = collateralFetch.value.collateralPosted
            }
            else -> {
                logger.warn(
                    "Collateral unavailable for {}, falling back to CSA threshold approximation",
                    counterpartyId,
                )
                val csaThreshold = primaryAgreement?.csaThreshold ?: 0.0
                collateralHeld = minOf(csaThreshold, totalNetExposure).coerceAtLeast(0.0)
                collateralPosted = 0.0
            }
        }

        // netNetExposure = netExposure - collateralHeld + collateralPosted, floored at 0.
        // Collateral received reduces exposure; collateral posted increases it.
        // We floor at zero — collateral cannot flip exposure to a receivable.
        val netNetExposure = (totalNetExposure - collateralHeld + collateralPosted).coerceAtLeast(0.0)

        // Wrong-way risk: financial-sector counterparties create wrong-way risk correlation.
        val wrongWayRiskFlags = computeWrongWayRiskFlags(counterparty)

        val snapshot = CounterpartyExposureSnapshot(
            counterpartyId = counterpartyId,
            calculatedAt = Instant.now(),
            pfeProfile = representativeProfile,
            currentNetExposure = totalNetExposure,
            peakPfe = peakPfe,
            cva = cvaResult?.cva,
            cvaEstimated = cvaResult?.isEstimated ?: false,
            nettingSetExposures = nettingSetExposures,
            collateralHeld = collateralHeld,
            collateralPosted = collateralPosted,
            netNetExposure = netNetExposure,
            wrongWayRiskFlags = wrongWayRiskFlags,
        )

        return repository.save(snapshot)
    }

    /**
     * Wrong-way risk arises when the counterparty's credit quality deteriorates at the same time
     * as our exposure to them increases.  Financial-sector counterparties are the primary vector:
     * they tend to be stressed precisely when financial markets are dislocated and our exposures peak.
     */
    private fun computeWrongWayRiskFlags(counterparty: CounterpartyDto): List<String> {
        val flags = mutableListOf<String>()
        if (counterparty.isFinancial) {
            flags.add("FINANCIAL_SECTOR_WRONG_WAY_RISK: counterparty sector correlated with market stress")
        }
        val sector = counterparty.sector.uppercase()
        if (sector in setOf("SOVEREIGN", "GOVERNMENT")) {
            flags.add("SOVEREIGN_WRONG_WAY_RISK: sovereign counterparty exposure may spike during crises")
        }
        return flags
    }

    /**
     * Computes CVA for the counterparty using its credit data from reference data service
     * and a pre-computed exposure profile (typically from a PFE run).
     *
     * Throws [IllegalStateException] if the counterparty has no credit data (both pd1y and
     * cdsSpreadBps are absent), because computing CVA without credit data would silently
     * return zero — which looks like "no risk" rather than "risk unknown".
     */
    suspend fun computeCVA(
        counterpartyId: String,
        exposureProfile: List<ExposureAtTenor>,
    ): CVAResult {
        val counterparty = when (val resp = referenceDataClient.getCounterparty(counterpartyId)) {
            is ClientResponse.Success -> resp.value
            else -> throw IllegalArgumentException("Counterparty not found: $counterpartyId")
        }

        if (!hasCreditData(counterparty)) {
            throw IllegalStateException(
                "Counterparty $counterpartyId has no credit data (pd1y and cdsSpreadBps are both absent); CVA cannot be computed"
            )
        }

        return counterpartyRiskClient.calculateCVA(
            counterpartyId = counterpartyId,
            exposureProfile = exposureProfile,
            lgd = counterparty.lgd,
            pd1y = counterparty.pd1y ?: 0.0,
            cdsSpreadBps = counterparty.cdsSpreadBps ?: 0.0,
            rating = counterparty.ratingSp ?: "",
            sector = counterparty.sector,
            riskFreeRate = 0.0,
        )
    }

    /**
     * Returns true when the counterparty has at least one source of credit data.
     * CVA is only meaningful when credit data is available; without it the calculation
     * would return zero, which is indistinguishable from "genuinely zero credit risk".
     */
    private fun hasCreditData(counterparty: CounterpartyDto): Boolean =
        counterparty.pd1y != null || counterparty.cdsSpreadBps != null

    suspend fun getLatestExposure(counterpartyId: String): CounterpartyExposureSnapshot? =
        repository.findLatestByCounterpartyId(counterpartyId)

    suspend fun getExposureHistory(counterpartyId: String, limit: Int = 90): List<CounterpartyExposureSnapshot> =
        repository.findByCounterpartyId(counterpartyId, limit)

    suspend fun getAllLatestExposures(): List<CounterpartyExposureSnapshot> =
        repository.findLatestForAllCounterparties()
}
