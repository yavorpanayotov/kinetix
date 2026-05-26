package com.kinetix.demo.schedule

import com.kinetix.demo.client.PositionServiceClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Default [StrategyIdResolver] used by the demo orchestrator at runtime.
 *
 * Resolves the set of strategies seeded for each demo book by calling
 * `GET /api/v1/books/{bookId}/strategies` on `position-service`
 * ([PositionServiceClient.listStrategies]). The result is cached per book on
 * the first successful call so the in-tick lookup in
 * [SimulatedTraderJob] is essentially free after warm-up.
 *
 * ## Fallback
 *
 * `position-service`'s `DevDataSeeder` seeds 2–3 named strategies per demo
 * book (kx-bg3). If the API call fails (network blip, service restart) or
 * returns an empty list (seed has not run yet on a fresh deploy), the
 * resolver falls back to the legacy synthesized id `"{bookId}-default"` so
 * the trader keeps producing trades and the failure surfaces as a
 * `strategy_not_found` 404 from `position-service` rather than a NPE in the
 * simulator. The fallback is NOT cached so the next tick re-attempts the
 * lookup against the upstream service.
 *
 * Operators can override the strategies for a specific book via the
 * [overrides] map — useful for ad-hoc demos where the seeder hasn't run yet
 * or for local development.
 */
class DefaultStrategyIdResolver(
    private val positionClient: PositionServiceClient,
    private val overrides: Map<String, List<String>> = emptyMap(),
) : StrategyIdResolver {

    private val logger = LoggerFactory.getLogger(DefaultStrategyIdResolver::class.java)
    private val cache: MutableMap<String, List<String>> = mutableMapOf()
    private val cacheMutex = Mutex()

    override suspend fun strategiesFor(bookId: String): List<String> {
        overrides[bookId]?.let { return it }

        cacheMutex.withLock { cache[bookId] }?.let { return it }

        val fetched = try {
            positionClient.listStrategies(bookId)
        } catch (failure: Exception) {
            logger.warn(
                "Failed to list strategies for bookId={} — falling back to synthesized default",
                bookId,
                failure,
            )
            return listOf(fallbackStrategyId(bookId))
        }

        if (fetched.isEmpty()) {
            logger.warn(
                "position-service returned no strategies for bookId={} — falling back to synthesized default",
                bookId,
            )
            return listOf(fallbackStrategyId(bookId))
        }

        cacheMutex.withLock { cache[bookId] = fetched }
        return fetched
    }

    private fun fallbackStrategyId(bookId: String): String = "$bookId-default"
}
