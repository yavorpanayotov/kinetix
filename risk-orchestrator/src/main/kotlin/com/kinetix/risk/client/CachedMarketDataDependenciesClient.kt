package com.kinetix.risk.client

import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.proto.risk.DataDependenciesResponse
import com.kinetix.risk.client.dtos.InstrumentDto
import com.kinetix.risk.model.FactorDecompositionSnapshot
import com.kinetix.risk.model.MarketDataValue
import com.kinetix.risk.model.TimeSeriesMarketData
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.model.VaRResult
import com.kinetix.risk.model.ValuationResult
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Wraps a [RiskEngineClient] and caches [discoverDependencies] responses keyed by
 * `(calculationType, confidenceLevel)`.
 *
 * Dependency declarations only change when the risk-engine is redeployed. The
 * [discoverDependencies] method returns the cached response immediately on
 * cache hits (no delegate call). Version-based invalidation is separated into
 * [probeAndRefreshIfVersionChanged], which should be called periodically (e.g.
 * on a scheduled timer or on risk-engine deploy notification) — it calls the
 * delegate once, compares the returned [DataDependenciesResponse.engineVersion]
 * against the cached version, and evicts the entry when the versions differ.
 *
 * All other [RiskEngineClient] methods are forwarded unchanged.
 */
class CachedMarketDataDependenciesClient(
    private val delegate: RiskEngineClient,
) : RiskEngineClient {

    private val logger = LoggerFactory.getLogger(CachedMarketDataDependenciesClient::class.java)

    private data class DependencyKey(
        val calculationType: String,
        val confidenceLevel: String,
    )

    private data class CachedEntry(
        val engineVersion: String,
        val response: DataDependenciesResponse,
    )

    private val cache = ConcurrentHashMap<DependencyKey, CachedEntry>()

    override suspend fun discoverDependencies(
        positions: List<Position>,
        calculationType: String,
        confidenceLevel: String,
        instrumentMap: Map<String, InstrumentDto>,
    ): DataDependenciesResponse {
        val key = DependencyKey(calculationType, confidenceLevel)
        val existing = cache[key]
        if (existing != null) {
            logger.debug(
                "Cache hit for ({}, {}) — engine_version={}",
                calculationType, confidenceLevel, existing.engineVersion,
            )
            return existing.response
        }

        val response = delegate.discoverDependencies(positions, calculationType, confidenceLevel, instrumentMap)
        cache[key] = CachedEntry(response.engineVersion, response)
        logger.info(
            "Cache miss for ({}, {}) — engine_version={} — cached {} dependencies",
            calculationType, confidenceLevel, response.engineVersion, response.dependenciesCount,
        )
        return response
    }

    /**
     * Probes the delegate for the given key and refreshes the cache entry if the
     * engine version has changed since the last population.
     *
     * Call this periodically — e.g. from a scheduled background job — or in
     * response to a risk-engine deploy notification. It performs exactly one
     * gRPC call per invocation regardless of whether the version changed.
     */
    suspend fun probeAndRefreshIfVersionChanged(
        positions: List<Position>,
        calculationType: String,
        confidenceLevel: String,
        instrumentMap: Map<String, InstrumentDto> = emptyMap(),
    ) {
        val key = DependencyKey(calculationType, confidenceLevel)
        val fresh = delegate.discoverDependencies(positions, calculationType, confidenceLevel, instrumentMap)
        val existing = cache[key]

        if (existing == null || existing.engineVersion != fresh.engineVersion) {
            cache[key] = CachedEntry(fresh.engineVersion, fresh)
            logger.info(
                "Risk-engine version change detected for ({}, {}): {} → {} — cache refreshed with {} dependencies",
                calculationType, confidenceLevel,
                existing?.engineVersion ?: "(none)", fresh.engineVersion,
                fresh.dependenciesCount,
            )
        } else {
            logger.debug(
                "Version probe for ({}, {}) — engine_version={} unchanged",
                calculationType, confidenceLevel, fresh.engineVersion,
            )
        }
    }

    /** Removes all cached entries. Useful in tests and for manual cache invalidation. */
    fun invalidateAll() {
        cache.clear()
    }

    // ---------- Pass-through methods ----------

    override suspend fun calculateVaR(
        request: VaRCalculationRequest,
        positions: List<Position>,
        marketData: List<MarketDataValue>,
        instrumentMap: Map<String, InstrumentDto>,
    ): VaRResult = delegate.calculateVaR(request, positions, marketData, instrumentMap)

    override suspend fun valuate(
        request: VaRCalculationRequest,
        positions: List<Position>,
        marketData: List<MarketDataValue>,
        instrumentMap: Map<String, InstrumentDto>,
    ): ValuationResult = delegate.valuate(request, positions, marketData, instrumentMap)

    override suspend fun decomposeFactorRisk(
        bookId: BookId,
        positions: List<Position>,
        marketData: Map<String, TimeSeriesMarketData>,
        totalVar: Double,
    ): FactorDecompositionSnapshot = delegate.decomposeFactorRisk(bookId, positions, marketData, totalVar)
}
