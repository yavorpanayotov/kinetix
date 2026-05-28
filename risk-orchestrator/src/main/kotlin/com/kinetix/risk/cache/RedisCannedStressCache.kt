package com.kinetix.risk.cache

import com.kinetix.risk.routes.dtos.CannedStressResultResponse
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Redis-backed [CannedStressCache]. Replaces the in-memory variant for the
 * canned stress-scenario tile so the result survives a restart of
 * risk-orchestrator and the UI tile stays populated until the next daily seed
 * (issue kx-wxy followup — risk-orchestrator restarting between seeds used to
 * leave the Risk overview blank for up to 24h).
 *
 * The default TTL is 25 hours — long enough for the next daily SOD re-seed
 * (09:00 UTC) to overwrite the entry before it expires, but short enough that
 * a permanently failed seed won't keep stale data around indefinitely.
 *
 * Reads and writes are best-effort: any Redis failure is logged at WARN and
 * treated as a cache miss, mirroring [RedisVaRCache].
 */
class RedisCannedStressCache(
    private val connection: StatefulRedisConnection<String, String>,
    private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
) : CannedStressCache {

    private val logger = LoggerFactory.getLogger(RedisCannedStressCache::class.java)
    private val sync = connection.sync()
    private val cacheJson = Json { ignoreUnknownKeys = true }

    override fun put(bookId: String, result: CannedStressResultResponse) {
        try {
            val key = keyFor(bookId)
            val value = Json.encodeToString(result)
            sync.set(key, value, SetArgs().ex(ttlSeconds))
        } catch (e: Exception) {
            logger.warn("Cache write failed for bookId={}, continuing without caching", bookId, e)
        }
    }

    override fun get(bookId: String): CannedStressResultResponse? {
        return try {
            val key = keyFor(bookId)
            val value = sync.get(key) ?: return null
            cacheJson.decodeFromString<CannedStressResultResponse>(value)
        } catch (e: Exception) {
            logger.warn("Cache read failed for bookId={}, treating as cache miss", bookId, e)
            null
        }
    }

    private fun keyFor(bookId: String): String =
        "canned-stress:v$CACHE_SCHEMA_VERSION:$bookId"

    companion object {
        const val CACHE_SCHEMA_VERSION = 1
        const val DEFAULT_TTL_SECONDS = 90_000L
    }
}
