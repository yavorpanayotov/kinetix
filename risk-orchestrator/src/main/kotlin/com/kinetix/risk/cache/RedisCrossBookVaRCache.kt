package com.kinetix.risk.cache

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.risk.model.BookVaRContribution
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ComponentBreakdown
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.CrossBookValuationResult
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Redis-backed [CrossBookVaRCache]. Mirrors [RedisCannedStressCache] in
 * purpose — survives a restart of risk-orchestrator so the firm-level
 * cross-book VaR tile stays populated between daily bootstrap seeds.
 *
 * The domain [CrossBookValuationResult] uses non-`@Serializable` types
 * (`BookId`, `Instant`, `UUID`), so we round-trip through
 * [CachedCrossBookValuationResult] using the same pattern as
 * [RedisVaRCache]'s `CachedValuationResult`.
 */
class RedisCrossBookVaRCache(
    private val connection: StatefulRedisConnection<String, String>,
    private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
) : CrossBookVaRCache {

    private val logger = LoggerFactory.getLogger(RedisCrossBookVaRCache::class.java)
    private val sync = connection.sync()
    private val cacheJson = Json { ignoreUnknownKeys = true }

    override fun put(groupId: String, result: CrossBookValuationResult) {
        try {
            val key = keyFor(groupId)
            val value = Json.encodeToString(CachedCrossBookValuationResult.from(result))
            sync.set(key, value, SetArgs().ex(ttlSeconds))
        } catch (e: Exception) {
            logger.warn("Cache write failed for groupId={}, continuing without caching", groupId, e)
        }
    }

    override fun get(groupId: String): CrossBookValuationResult? {
        return try {
            val key = keyFor(groupId)
            val value = sync.get(key) ?: return null
            cacheJson.decodeFromString<CachedCrossBookValuationResult>(value).toDomain()
        } catch (e: Exception) {
            logger.warn("Cache read failed for groupId={}, treating as cache miss", groupId, e)
            null
        }
    }

    private fun keyFor(groupId: String): String =
        "cross-book-var:v$CACHE_SCHEMA_VERSION:$groupId"

    companion object {
        const val CACHE_SCHEMA_VERSION = 1
        const val DEFAULT_TTL_SECONDS = 90_000L
    }
}

@Serializable
internal data class CachedComponent(
    val assetClass: String,
    val varContribution: Double,
    val percentageOfTotal: Double,
)

@Serializable
internal data class CachedBookContribution(
    val bookId: String,
    val varContribution: Double,
    val percentageOfTotal: Double,
    val standaloneVar: Double,
    val diversificationBenefit: Double,
    val marginalVar: Double = 0.0,
    val incrementalVar: Double = 0.0,
)

@Serializable
internal data class CachedCrossBookValuationResult(
    val portfolioGroupId: String,
    val bookIds: List<String>,
    val calculationType: String,
    val confidenceLevel: String,
    val varValue: Double,
    val expectedShortfall: Double,
    val componentBreakdown: List<CachedComponent>,
    val bookContributions: List<CachedBookContribution>,
    val totalStandaloneVar: Double,
    val diversificationBenefit: Double,
    val calculatedAt: String,
    val modelVersion: String? = null,
    val monteCarloSeed: Long = 0,
    val jobId: String? = null,
) {
    fun toDomain(): CrossBookValuationResult = CrossBookValuationResult(
        portfolioGroupId = portfolioGroupId,
        bookIds = bookIds.map { BookId(it) },
        calculationType = CalculationType.valueOf(calculationType),
        confidenceLevel = ConfidenceLevel.valueOf(confidenceLevel),
        varValue = varValue,
        expectedShortfall = expectedShortfall,
        componentBreakdown = componentBreakdown.map {
            ComponentBreakdown(AssetClass.valueOf(it.assetClass), it.varContribution, it.percentageOfTotal)
        },
        bookContributions = bookContributions.map {
            BookVaRContribution(
                bookId = BookId(it.bookId),
                varContribution = it.varContribution,
                percentageOfTotal = it.percentageOfTotal,
                standaloneVar = it.standaloneVar,
                diversificationBenefit = it.diversificationBenefit,
                marginalVar = it.marginalVar,
                incrementalVar = it.incrementalVar,
            )
        },
        totalStandaloneVar = totalStandaloneVar,
        diversificationBenefit = diversificationBenefit,
        calculatedAt = Instant.parse(calculatedAt),
        modelVersion = modelVersion,
        monteCarloSeed = monteCarloSeed,
        jobId = jobId?.let { UUID.fromString(it) },
    )

    companion object {
        fun from(r: CrossBookValuationResult): CachedCrossBookValuationResult =
            CachedCrossBookValuationResult(
                portfolioGroupId = r.portfolioGroupId,
                bookIds = r.bookIds.map { it.value },
                calculationType = r.calculationType.name,
                confidenceLevel = r.confidenceLevel.name,
                varValue = r.varValue,
                expectedShortfall = r.expectedShortfall,
                componentBreakdown = r.componentBreakdown.map {
                    CachedComponent(it.assetClass.name, it.varContribution, it.percentageOfTotal)
                },
                bookContributions = r.bookContributions.map {
                    CachedBookContribution(
                        bookId = it.bookId.value,
                        varContribution = it.varContribution,
                        percentageOfTotal = it.percentageOfTotal,
                        standaloneVar = it.standaloneVar,
                        diversificationBenefit = it.diversificationBenefit,
                        marginalVar = it.marginalVar,
                        incrementalVar = it.incrementalVar,
                    )
                },
                totalStandaloneVar = r.totalStandaloneVar,
                diversificationBenefit = r.diversificationBenefit,
                calculatedAt = r.calculatedAt.toString(),
                modelVersion = r.modelVersion,
                monteCarloSeed = r.monteCarloSeed,
                jobId = r.jobId?.toString(),
            )
    }
}
