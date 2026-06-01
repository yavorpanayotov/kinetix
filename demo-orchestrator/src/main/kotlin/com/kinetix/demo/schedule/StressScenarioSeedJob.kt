package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.profile.DemoBookProfile
import com.kinetix.demo.profile.DemoBookProfiles
import org.slf4j.LoggerFactory

/**
 * Seeds a canned stress-scenario result for every demo book so the Risk
 * overview's stress tile is populated immediately after bootstrap and refreshed
 * at SOD (issue kx-wxy).
 *
 * For each profile in [books], the job fires
 * `POST /api/v1/risk/stress/{bookId}/canned/{scenarioName}` against
 * risk-orchestrator. Risk-orchestrator caches the delta-PV result in-memory and
 * the UI reads it back via the matching GET endpoint.
 *
 * ## Idempotency
 *
 * Re-running [runOnce] is safe — risk-orchestrator overwrites its cached
 * result per book on every POST, so the tile always reflects the latest sweep.
 *
 * ## Failure isolation
 *
 * One failing book never blocks the rest. Per-book failures are logged at
 * WARN level and swallowed so the sweep continues. This mirrors the pattern
 * used by [LimitSeedJob].
 *
 * ## Scheduling
 *
 * Cron / startup wiring is intentionally NOT here — `Application.kt` is
 * responsible for invoking [runOnce] at bootstrap (after DemoVaRBootstrapJob)
 * and on the daily SOD schedule.
 *
 * @property client wire to `risk-orchestrator` HTTP routes.
 * @property books supplies the set of book profiles to sweep. Defaults to
 *     the 8 books defined in [DemoBookProfiles].
 * @property scenarioName canned scenario to request. Defaults to
 *     `"+100BPS_PARALLEL"` — the only canned scenario the engine ships with
 *     today. Multi-scenario support is a follow-up.
 */
class StressScenarioSeedJob(
    private val client: RiskOrchestratorClient,
    private val books: List<DemoBookProfile> = DemoBookProfiles.all(),
    private val scenarioName: String = DEFAULT_CANNED_SCENARIO,
) {
    private val logger = LoggerFactory.getLogger(StressScenarioSeedJob::class.java)

    /**
     * Fires the canned scenario once for every book in [books]. Failures are
     * logged and isolated per-book so a single broken book does not abort
     * the sweep.
     */
    suspend fun runOnce() {
        logger.info(
            "Starting StressScenarioSeedJob sweep over {} books for scenario {}",
            books.size, scenarioName,
        )
        for (profile in books) {
            seedForBook(profile)
        }
        logger.info("Finished StressScenarioSeedJob sweep")
    }

    private suspend fun seedForBook(profile: DemoBookProfile) {
        val bookId = profile.bookId
        // 1. Canned single-scenario seed for the Risk overview tile (kx-wxy).
        try {
            client.runCannedStressScenario(bookId, scenarioName)
            logger.debug("Seeded canned stress scenario {} for book {}", scenarioName, bookId)
        } catch (failure: Exception) {
            logger.warn(
                "Failed to seed canned stress scenario {} for book {} — continuing",
                scenarioName, bookId, failure,
            )
        }
        // 2. Full multi-scenario sweep so the Scenarios tab comparison grid has
        //    a "latest run" to render on cold open (kx-kjse). Isolated from the
        //    canned seed so either failing never blocks the other.
        try {
            client.runAllStressScenarios(bookId)
            logger.debug("Seeded full stress sweep for book {}", bookId)
        } catch (failure: Exception) {
            logger.warn(
                "Failed to seed full stress sweep for book {} — continuing",
                bookId, failure,
            )
        }
    }

    companion object {
        /**
         * Default canned scenario name surfaced on the Risk overview tile.
         * Must match the pre-registered scenario name in risk-engine
         * (`risk-engine/src/kinetix_risk/stress/scenarios.py`).
         */
        const val DEFAULT_CANNED_SCENARIO: String = "+100BPS_PARALLEL"
    }
}
