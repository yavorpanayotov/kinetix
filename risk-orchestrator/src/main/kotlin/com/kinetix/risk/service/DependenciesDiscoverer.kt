package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.Position
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.model.DiscoveredDependency
import org.slf4j.LoggerFactory

/**
 * Instrument ID of the S&P 500 benchmark index, used as the EQUITY_BETA
 * factor proxy for factor-based risk decomposition.
 */
const val SPX_BENCHMARK_INSTRUMENT_ID = "IDX-SPX"

/**
 * Number of trading days of historical prices requested for the SPX benchmark.
 * Exceeds the 252-day OLS window to provide a buffer for calendar gaps.
 */
const val SPX_HISTORY_LOOKBACK_DAYS = 300

class DependenciesDiscoverer(
    private val riskEngineClient: RiskEngineClient,
) {
    private val logger = LoggerFactory.getLogger(DependenciesDiscoverer::class.java)

    suspend fun discover(
        positions: List<Position>,
        calculationType: String,
        confidenceLevel: String,
    ): List<DiscoveredDependency> {
        val depsResponse = try {
            riskEngineClient.discoverDependencies(positions, calculationType, confidenceLevel)
        } catch (e: Exception) {
            logger.warn("Failed to discover market data dependencies, proceeding without market data", e)
            return emptyList()
        }

        val seen = mutableSetOf<Triple<String, String, Map<String, String>>>()
        val result = depsResponse.dependenciesList.mapNotNull { dep ->
            val dataTypeName = dep.dataType.name
            val instrumentId = dep.instrumentId
            val key = Triple(dataTypeName, instrumentId, dep.parametersMap)

            if (!seen.add(key)) return@mapNotNull null

            DiscoveredDependency(
                dataType = dataTypeName,
                instrumentId = instrumentId,
                assetClass = dep.assetClass,
                parameters = dep.parametersMap,
                required = dep.required,
            )
        }.toMutableList()

        // When equity positions are present, inject a HISTORICAL_PRICES dependency
        // for the SPX benchmark index. This data is consumed by FactorRiskService
        // to estimate equity beta loadings via OLS regression.
        val hasEquityPositions = positions.any { it.assetClass == AssetClass.EQUITY }
        if (hasEquityPositions) {
            val spxKey = Triple("HISTORICAL_PRICES", SPX_BENCHMARK_INSTRUMENT_ID, mapOf("lookbackDays" to SPX_HISTORY_LOOKBACK_DAYS.toString()))
            if (seen.add(spxKey)) {
                result.add(
                    DiscoveredDependency(
                        dataType = "HISTORICAL_PRICES",
                        instrumentId = SPX_BENCHMARK_INSTRUMENT_ID,
                        assetClass = "EQUITY",
                        parameters = mapOf("lookbackDays" to SPX_HISTORY_LOOKBACK_DAYS.toString()),
                    )
                )
            }
        }

        return result
    }
}
