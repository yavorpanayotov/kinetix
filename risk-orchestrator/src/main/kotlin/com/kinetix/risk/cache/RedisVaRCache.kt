package com.kinetix.risk.cache

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.BookId
import com.kinetix.risk.model.*
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class RedisVaRCache(
    private val connection: StatefulRedisConnection<String, String>,
    private val ttlSeconds: Long = 300L,
) : VaRCache {

    private val logger = LoggerFactory.getLogger(RedisVaRCache::class.java)
    private val sync = connection.sync()
    private val cacheJson = Json { ignoreUnknownKeys = true }

    override fun put(bookId: String, result: ValuationResult) {
        try {
            val key = keyFor(bookId)
            val value = Json.encodeToString(CachedValuationResult.from(result))
            sync.set(key, value, SetArgs().ex(ttlSeconds))
        } catch (e: Exception) {
            logger.warn("Cache write failed for bookId={}, continuing without caching", bookId, e)
        }
    }

    override fun get(bookId: String): ValuationResult? {
        return try {
            val key = keyFor(bookId)
            val value = sync.get(key) ?: return null
            cacheJson.decodeFromString<CachedValuationResult>(value).toValuationResult()
        } catch (e: Exception) {
            logger.warn("Cache read failed for bookId={}, treating as cache miss", bookId, e)
            null
        }
    }

    private fun keyFor(bookId: String): String = "var:v$CACHE_SCHEMA_VERSION:$bookId"

    companion object {
        const val CACHE_SCHEMA_VERSION = 1
    }
}

@Serializable
internal data class CachedComponentBreakdown(
    val assetClass: String,
    val varContribution: Double,
    val percentageOfTotal: Double,
)

@Serializable
internal data class CachedGreekValues(
    val assetClass: String,
    val delta: Double,
    val gamma: Double,
    val vega: Double,
)

@Serializable
internal data class CachedGreeksResult(
    val assetClassGreeks: List<CachedGreekValues>,
    val theta: Double,
    val rho: Double,
)

@Serializable
internal data class CachedPositionRisk(
    val instrumentId: String,
    val assetClass: String,
    val marketValue: String,
    val delta: Double?,
    val gamma: Double?,
    val vega: Double?,
    val varContribution: String,
    val esContribution: String,
    val percentageOfTotal: String,
    // Per-instrument Theta / Rho / DV01. Default null so cached entries
    // written before the trader-review P0 #2 fix still deserialise.
    val theta: Double? = null,
    val rho: Double? = null,
    val dv01: Double? = null,
)

@Serializable
internal data class CachedPositionGreek(
    val instrumentId: String,
    val delta: Double,
    val gamma: Double,
    val vega: Double,
    val theta: Double,
    val rho: Double,
)

@Serializable
internal data class CachedValuationResult(
    val bookId: String,
    val calculationType: String,
    val confidenceLevel: String,
    val varValue: Double?,
    val expectedShortfall: Double?,
    val componentBreakdown: List<CachedComponentBreakdown>,
    val greeks: CachedGreeksResult?,
    val calculatedAt: String,
    val computedOutputs: List<String>,
    val pvValue: Double?,
    val positionRisk: List<CachedPositionRisk>,
    val jobId: String?,
    val marketDataComplete: Boolean = true,
    val positionGreeks: List<CachedPositionGreek> = emptyList(),
) {
    fun toValuationResult(): ValuationResult = ValuationResult(
        bookId = BookId(bookId),
        calculationType = CalculationType.valueOf(calculationType),
        confidenceLevel = ConfidenceLevel.valueOf(confidenceLevel),
        varValue = varValue,
        expectedShortfall = expectedShortfall,
        componentBreakdown = componentBreakdown.map {
            ComponentBreakdown(AssetClass.valueOf(it.assetClass), it.varContribution, it.percentageOfTotal)
        },
        greeks = greeks?.let { g ->
            GreeksResult(
                assetClassGreeks = g.assetClassGreeks.map {
                    GreekValues(AssetClass.valueOf(it.assetClass), it.delta, it.gamma, it.vega)
                },
                theta = g.theta,
                rho = g.rho,
            )
        },
        calculatedAt = Instant.parse(calculatedAt),
        computedOutputs = computedOutputs.map { ValuationOutput.valueOf(it) }.toSet(),
        pvValue = pvValue,
        positionRisk = positionRisk.map {
            PositionRisk(
                instrumentId = InstrumentId(it.instrumentId),
                assetClass = AssetClass.valueOf(it.assetClass),
                marketValue = BigDecimal(it.marketValue),
                delta = it.delta,
                gamma = it.gamma,
                vega = it.vega,
                varContribution = BigDecimal(it.varContribution),
                esContribution = BigDecimal(it.esContribution),
                percentageOfTotal = BigDecimal(it.percentageOfTotal),
                theta = it.theta,
                rho = it.rho,
                dv01 = it.dv01,
            )
        },
        jobId = jobId?.let { UUID.fromString(it) },
        marketDataComplete = marketDataComplete,
        positionGreeks = positionGreeks.map {
            PositionGreek(
                instrumentId = it.instrumentId,
                delta = it.delta,
                gamma = it.gamma,
                vega = it.vega,
                theta = it.theta,
                rho = it.rho,
            )
        },
    )

    companion object {
        fun from(result: ValuationResult): CachedValuationResult = CachedValuationResult(
            bookId = result.bookId.value,
            calculationType = result.calculationType.name,
            confidenceLevel = result.confidenceLevel.name,
            varValue = result.varValue,
            expectedShortfall = result.expectedShortfall,
            componentBreakdown = result.componentBreakdown.map {
                CachedComponentBreakdown(it.assetClass.name, it.varContribution, it.percentageOfTotal)
            },
            greeks = result.greeks?.let { g ->
                CachedGreeksResult(
                    assetClassGreeks = g.assetClassGreeks.map {
                        CachedGreekValues(it.assetClass.name, it.delta, it.gamma, it.vega)
                    },
                    theta = g.theta,
                    rho = g.rho,
                )
            },
            calculatedAt = result.calculatedAt.toString(),
            computedOutputs = result.computedOutputs.map { it.name },
            pvValue = result.pvValue,
            positionRisk = result.positionRisk.map {
                CachedPositionRisk(
                    instrumentId = it.instrumentId.value,
                    assetClass = it.assetClass.name,
                    marketValue = it.marketValue.toPlainString(),
                    delta = it.delta,
                    gamma = it.gamma,
                    vega = it.vega,
                    varContribution = it.varContribution.toPlainString(),
                    esContribution = it.esContribution.toPlainString(),
                    percentageOfTotal = it.percentageOfTotal.toPlainString(),
                    theta = it.theta,
                    rho = it.rho,
                    dv01 = it.dv01,
                )
            },
            jobId = result.jobId?.toString(),
            marketDataComplete = result.marketDataComplete,
            positionGreeks = result.positionGreeks.map {
                CachedPositionGreek(
                    instrumentId = it.instrumentId,
                    delta = it.delta,
                    gamma = it.gamma,
                    vega = it.vega,
                    theta = it.theta,
                    rho = it.rho,
                )
            },
        )
    }
}
