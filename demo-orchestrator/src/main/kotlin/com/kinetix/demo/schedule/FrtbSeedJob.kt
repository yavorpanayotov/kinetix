package com.kinetix.demo.schedule

import com.kinetix.demo.client.RegulatoryServiceClient
import com.kinetix.demo.profile.DemoBookProfile
import com.kinetix.demo.profile.DemoBookProfiles
import org.slf4j.LoggerFactory

/**
 * Seeds a "latest" FRTB capital-charge result for every demo book so the
 * regulatory FRTB endpoint returns data immediately after bootstrap and is
 * refreshed at SOD (issue kx-kzbs).
 *
 * For each profile in [books] the job fires
 * `POST /api/v1/regulatory/frtb/{bookId}/calculate` against regulatory-service,
 * which computes the charge (delegating to risk-orchestrator) and persists the
 * result. Without this step `GET /api/v1/regulatory/frtb/{bookId}/latest`
 * returns 404 for every book on a fresh demo because no FRTB result has ever
 * been stored.
 *
 * ## Idempotency
 *
 * Re-running [runOnce] is safe — each calculation persists a fresh record and
 * the `…/latest` read path always returns the most recent one, so the endpoint
 * always reflects the latest sweep.
 *
 * ## Failure isolation
 *
 * One failing book never blocks the rest. Per-book failures are logged at WARN
 * level and swallowed so the sweep continues. This mirrors the pattern used by
 * [StressScenarioSeedJob] and [LimitSeedJob].
 *
 * ## Scheduling
 *
 * Cron / startup wiring lives in `Application.kt`, which invokes [runOnce] at
 * bootstrap (after positions are seeded) and on the daily SOD schedule.
 *
 * @property client wire to `regulatory-service` HTTP routes.
 * @property books supplies the set of book profiles to sweep. Defaults to the
 *     8 books defined in [DemoBookProfiles].
 */
class FrtbSeedJob(
    private val client: RegulatoryServiceClient,
    private val books: List<DemoBookProfile> = DemoBookProfiles.all(),
) {
    private val logger = LoggerFactory.getLogger(FrtbSeedJob::class.java)

    /**
     * Triggers an FRTB calculation once for every book in [books]. Failures are
     * logged and isolated per-book so a single broken book does not abort the
     * sweep.
     */
    suspend fun runOnce() {
        logger.info("Starting FrtbSeedJob sweep over {} books", books.size)
        for (profile in books) {
            seedForBook(profile)
        }
        logger.info("Finished FrtbSeedJob sweep")
    }

    private suspend fun seedForBook(profile: DemoBookProfile) {
        val bookId = profile.bookId
        try {
            client.calculateFrtb(bookId)
            logger.debug("Seeded latest FRTB result for book {}", bookId)
        } catch (failure: Exception) {
            logger.warn(
                "Failed to seed FRTB result for book {} — continuing",
                bookId, failure,
            )
        }
    }
}
