package com.kinetix.demo.schedule

import com.kinetix.demo.client.LimitType
import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.profile.DemoBookProfile
import com.kinetix.demo.profile.DemoBookProfiles
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Seeds VaR and Delta limits for every demo book so the UI has populated
 * utilisation gauges and the simulated trading flow has thresholds to breach.
 *
 * For each profile in [books], the job:
 *   - reads the current book exposure via [RiskOrchestratorClient.readBookExposure],
 *   - posts a `VAR_95` limit at [varFactor] of the current 95% VaR (when > 0),
 *   - posts a `DELTA_ABS` limit at [deltaFactor] of the current absolute delta
 *     (only when the upstream snapshot surfaces a non-null, positive value).
 *
 * The thresholds are deliberately tight (`~80%` / `~70%` of current exposure)
 * so the demo trading flow breaches a handful of limits per day without
 * tripping constantly. Idempotency is owned by the upstream
 * `POST /api/v1/risk/budgets` route, which upserts on
 * `(entityLevel, entityId, budgetType)` — re-running this job simply updates
 * the thresholds to the latest exposure.
 *
 * Cron / startup wiring is intentionally NOT here — `Application.kt` is
 * responsible for invoking [runOnce] at boot and on the daily schedule.
 */
class LimitSeedJob(
    private val client: RiskOrchestratorClient,
    private val books: List<DemoBookProfile> = DemoBookProfiles.all(),
    private val varFactor: BigDecimal = "0.80".toBigDecimal(),
    private val deltaFactor: BigDecimal = "0.70".toBigDecimal(),
) {

    private val logger = LoggerFactory.getLogger(LimitSeedJob::class.java)

    /**
     * Performs a single end-to-end pass: read exposures and seed limits for
     * every book in [books]. Per-book failures (read or seed) are logged and
     * swallowed so a single broken book does not abort the whole sweep.
     */
    suspend fun runOnce() {
        logger.info("Starting LimitSeedJob sweep over {} books", books.size)
        for (profile in books) {
            seedLimitsForBook(profile)
        }
        logger.info("Finished LimitSeedJob sweep")
    }

    private suspend fun seedLimitsForBook(profile: DemoBookProfile) {
        val bookId = profile.bookId
        val snapshot = try {
            client.readBookExposure(bookId)
        } catch (failure: Exception) {
            logger.warn("Failed to read exposure for book {} — skipping", bookId, failure)
            return
        }

        if (snapshot.varValue > BigDecimal.ZERO) {
            val threshold = snapshot.varValue
                .multiply(varFactor)
                .setScale(2, RoundingMode.HALF_UP)
            postLimit(bookId, LimitType.VAR_95, threshold)
        } else {
            logger.warn(
                "book {} has non-positive varValue ({}) — skipping VAR_95 limit",
                bookId,
                snapshot.varValue,
            )
        }

        val absoluteDelta = snapshot.absoluteDelta
        when {
            absoluteDelta == null -> logger.warn(
                "book {} has no aggregate delta — skipping DELTA_ABS limit",
                bookId,
            )

            absoluteDelta > BigDecimal.ZERO -> {
                val threshold = absoluteDelta
                    .multiply(deltaFactor)
                    .setScale(2, RoundingMode.HALF_UP)
                postLimit(bookId, LimitType.DELTA_ABS, threshold)
            }

            else -> logger.warn(
                "book {} has non-positive absoluteDelta ({}) — skipping DELTA_ABS limit",
                bookId,
                absoluteDelta,
            )
        }
    }

    private suspend fun postLimit(bookId: String, limitType: LimitType, threshold: BigDecimal) {
        try {
            client.seedLimit(bookId, limitType, threshold)
            logger.debug("Seeded {} limit for book {} at threshold {}", limitType, bookId, threshold)
        } catch (failure: Exception) {
            logger.warn(
                "Failed to seed {} limit for book {} at threshold {} — continuing",
                limitType,
                bookId,
                threshold,
                failure,
            )
        }
    }
}
