package com.kinetix.risk.service

import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.PositionServiceClient
import com.kinetix.risk.client.ReferenceDataServiceClient
import com.kinetix.risk.client.SaCcrClient
import com.kinetix.risk.client.SaCcrPositionInput
import com.kinetix.risk.client.SaCcrResult
import com.kinetix.risk.client.dtos.CounterpartyTradeDto
import com.kinetix.risk.persistence.SaCcrResultRepository
import org.slf4j.LoggerFactory

/**
 * Orchestrates SA-CCR (BCBS 279) regulatory capital calculations.
 *
 * SA-CCR is the REGULATORY capital model — deterministic, formulaic.
 * It is distinct from the Monte Carlo PFE model in CounterpartyRiskOrchestrationService.
 *
 * Per spec rule `CalculateSaCcr` (counterparty-risk.allium), SA-CCR operates on a
 * `(counterparty_id, netting_set_id)` pair: a counterparty's trades and collateral are
 * partitioned by their real ISDA/GMRA netting agreement, and EAD is computed per netting
 * set.  Netting trades across legal netting boundaries would materially understate
 * regulatory capital, so each set is calculated independently — mirroring the per-netting-set
 * Monte Carlo PFE pipeline in [CounterpartyRiskOrchestrationService.computeAndPersistPFE].
 */
class SaCcrService(
    private val referenceDataClient: ReferenceDataServiceClient,
    private val saCcrClient: SaCcrClient,
    private val positionServiceClient: PositionServiceClient? = null,
    private val resultRepository: SaCcrResultRepository? = null,
) {
    private val logger = LoggerFactory.getLogger(SaCcrService::class.java)

    /**
     * Computes SA-CCR EAD for every netting set on the counterparty.
     *
     * Trades are grouped by their real netting-agreement assignment (instrument ->
     * nettingSetId, fetched from position-service — the same source the MC PFE pipeline
     * reads).  Trades with no assignment fall into a synthetic `"$counterpartyId-UNASSIGNED"`
     * set and are calculated standalone with no cross-set netting benefit.  A counterparty
     * with no trades yields a single result for its primary netting agreement so downstream
     * regulatory reporting still produces a coherent figure.
     */
    suspend fun calculateAllSaCcr(
        counterpartyId: String,
        collateralNet: Double = 0.0,
    ): List<SaCcrResult> {
        requireKnownCounterparty(counterpartyId)
        val groups = nettingSetGroups(counterpartyId)
        return groups.map { (setId, positions) ->
            computeForSet(counterpartyId, setId, positions, collateralNet)
        }
    }

    /**
     * Computes SA-CCR EAD for a single named netting set on the counterparty.
     *
     * @throws IllegalArgumentException if the counterparty is unknown, or if the netting set
     *   is not one of the counterparty's netting agreements / trade groupings.
     */
    suspend fun calculateSaCcr(
        counterpartyId: String,
        nettingSetId: String,
        collateralNet: Double = 0.0,
    ): SaCcrResult {
        requireKnownCounterparty(counterpartyId)
        val groups = nettingSetGroups(counterpartyId)
        val positions = groups[nettingSetId]
            ?: throw IllegalArgumentException(
                "Netting set not found for counterparty $counterpartyId: $nettingSetId",
            )
        return computeForSet(counterpartyId, nettingSetId, positions, collateralNet)
    }

    private suspend fun requireKnownCounterparty(counterpartyId: String) {
        when (referenceDataClient.getCounterparty(counterpartyId)) {
            is ClientResponse.Success -> Unit
            else -> throw IllegalArgumentException("Counterparty not found: $counterpartyId")
        }
    }

    /**
     * Partitions the counterparty's trades into netting-set buckets, keyed by netting-set id.
     *
     * Mirrors the MC PFE grouping in [CounterpartyRiskOrchestrationService]:
     *   - instrument -> nettingSetId comes from position-service;
     *   - trades with no assignment go to `"$counterpartyId-UNASSIGNED"`;
     *   - if there are no trades at all, the primary netting agreement is returned with an
     *     empty trade list so a result is still produced.
     */
    private suspend fun nettingSetGroups(
        counterpartyId: String,
    ): Map<String, List<SaCcrPositionInput>> {
        val trades = fetchTradesForCounterparty(counterpartyId)
        val instrumentNettingSets = fetchInstrumentNettingSets(counterpartyId)

        val unassigned = "$counterpartyId-UNASSIGNED"
        val tradesByNettingSet: Map<String, List<SaCcrPositionInput>> = trades
            .groupBy { trade -> instrumentNettingSets[trade.instrumentId] ?: unassigned }
            .mapValues { (_, setTrades) -> setTrades.map { it.toSaCcrInput() } }

        if (tradesByNettingSet.isNotEmpty()) return tradesByNettingSet

        // No trades: anchor the result on the primary netting agreement so regulatory
        // reporting still sees one (empty) netting set rather than nothing at all.
        val primary = fetchPrimaryNettingSetId(counterpartyId)
        return mapOf(primary to emptyList())
    }

    private suspend fun computeForSet(
        counterpartyId: String,
        nettingSetId: String,
        positions: List<SaCcrPositionInput>,
        collateralNet: Double,
    ): SaCcrResult {
        val result = saCcrClient.calculateSaCcr(
            nettingSetId = nettingSetId,
            counterpartyId = counterpartyId,
            positions = positions,
            collateralNet = collateralNet,
        )

        // Persist result for regulatory audit trail and historical reporting.
        try {
            resultRepository?.save(result, positions.size, collateralNet)
        } catch (e: Exception) {
            logger.error(
                "Failed to persist SA-CCR result for counterparty {} netting set {}: {}",
                counterpartyId,
                nettingSetId,
                e.message,
            )
        }

        return result
    }

    private suspend fun fetchTradesForCounterparty(counterpartyId: String): List<CounterpartyTradeDto> {
        val client = positionServiceClient ?: return emptyList()
        return when (val resp = client.getTradesByCounterparty(counterpartyId)) {
            is ClientResponse.Success -> resp.value
            else -> {
                logger.warn(
                    "Could not fetch trades for counterparty {} from position-service: {}",
                    counterpartyId,
                    resp,
                )
                emptyList()
            }
        }
    }

    private suspend fun fetchInstrumentNettingSets(counterpartyId: String): Map<String, String> {
        val client = positionServiceClient ?: return emptyMap()
        return when (
            val resp = try {
                client.getInstrumentNettingSets(counterpartyId)
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
    }

    private suspend fun fetchPrimaryNettingSetId(counterpartyId: String): String {
        val agreements = when (val resp = referenceDataClient.getNettingAgreements(counterpartyId)) {
            is ClientResponse.Success -> resp.value
            else -> emptyList()
        }
        return agreements.firstOrNull()?.nettingSetId ?: "$counterpartyId-DEFAULT"
    }
}

private fun CounterpartyTradeDto.toSaCcrInput() = SaCcrPositionInput(
    instrumentId = instrumentId,
    assetClass = assetClass,
    marketValue = priceAmount.toDouble() * quantity.toDouble() * if (side == "SELL") -1.0 else 1.0,
    notional = priceAmount.toDouble() * quantity.toDouble(),
    currency = priceCurrency,
    payReceive = if (side == "BUY") "PAY_FIXED" else "RECEIVE_FIXED",
    maturityDate = "",
    isOption = false,
    spotPrice = priceAmount.toDouble(),
    strike = 0.0,
    impliedVol = 0.0,
    expiryDays = 0,
    optionType = "",
    quantity = quantity.toDouble(),
)
