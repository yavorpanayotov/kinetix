package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.profile.DemoBookProfile
import com.kinetix.demo.profile.DemoBookProfiles
import org.slf4j.LoggerFactory

/**
 * Triggers a Key Rate Duration computation for every rate-oriented demo book so
 * the KRD endpoint returns populated data on the demo (issue kx-l8s7).
 *
 * `GET /api/v1/risk/krd/{bookId}` computes KRD on demand from a book's
 * fixed-income (bond) positions and returns empty `instruments`/`aggregated`
 * for books with none. Only the rate-oriented demo books
 * (e.g. `fixed-income`, `macro-hedge`) hold government bonds, so the job fires
 * the trigger for exactly those books — determined by inspecting each profile's
 * instrument ids through [DemoInstrumentTaxonomy] rather than a hard-coded
 * book list, so adding a bond to another book automatically warms its KRD too.
 *
 * ## Idempotency
 *
 * Re-running [runOnce] is safe — KRD is computed fresh from live positions on
 * every call.
 *
 * ## Failure isolation
 *
 * One failing book never blocks the rest. Per-book failures are logged at WARN
 * level and swallowed so the sweep continues. This mirrors the pattern used by
 * [StressScenarioSeedJob], [FrtbSeedJob] and [LimitSeedJob].
 *
 * ## Scheduling
 *
 * Cron / startup wiring lives in `Application.kt`, which invokes [runOnce] at
 * bootstrap and on the daily SOD schedule.
 *
 * @property client wire to `risk-orchestrator` HTTP routes.
 * @property books supplies the candidate book profiles. Defaults to the 8 books
 *     defined in [DemoBookProfiles]; only those holding government bonds are
 *     actually triggered.
 */
class KrdSnapshotSeedJob(
    private val client: RiskOrchestratorClient,
    private val books: List<DemoBookProfile> = DemoBookProfiles.all(),
) {
    private val logger = LoggerFactory.getLogger(KrdSnapshotSeedJob::class.java)

    /**
     * Triggers a KRD computation once for every rate-oriented book. Failures
     * are logged and isolated per-book so a single broken book does not abort
     * the sweep.
     */
    suspend fun runOnce() {
        val rateBooks = books.filter { it.holdsGovernmentBond() }
        logger.info(
            "Starting KrdSnapshotSeedJob sweep over {} rate-oriented books (of {} total)",
            rateBooks.size, books.size,
        )
        for (profile in rateBooks) {
            seedForBook(profile)
        }
        logger.info("Finished KrdSnapshotSeedJob sweep")
    }

    private suspend fun seedForBook(profile: DemoBookProfile) {
        val bookId = profile.bookId
        try {
            client.triggerKrdSnapshot(bookId)
            logger.debug("Triggered KRD computation for book {}", bookId)
        } catch (failure: Exception) {
            logger.warn(
                "Failed to trigger KRD computation for book {} — continuing",
                bookId, failure,
            )
        }
    }

    private fun DemoBookProfile.holdsGovernmentBond(): Boolean =
        instrumentIds.any { id ->
            DemoInstrumentTaxonomy.classify(id)?.instrumentType == "GOVERNMENT_BOND"
        }
}
