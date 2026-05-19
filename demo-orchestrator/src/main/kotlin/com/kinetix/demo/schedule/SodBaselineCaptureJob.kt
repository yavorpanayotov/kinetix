package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.profile.DemoBookProfile
import com.kinetix.demo.profile.DemoBookProfiles
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Captures the start-of-day (SOD) risk baseline for every demo book once per
 * simulated trading day.
 *
 * This job is the demo-side trigger for `risk-orchestrator`'s
 * [`POST /api/v1/risk/sod-snapshot/{bookId}`] route. Without a baseline the
 * P&L attribution endpoint returns `412 Precondition Failed`, the
 * `Set as Baseline` button in the UI is the only way out, and the P&L
 * Waterfall chart on the P&L tab renders zero for Gamma / Vega / Theta /
 * Rho (Delta still pops up because it falls back to the latest VaR
 * snapshot). Capturing the baseline at trading-day open closes that gap
 * automatically — see plans/ui-fix-v1.md checkbox 8.1.
 *
 * The wider `risk-orchestrator` `ScheduledSodSnapshotJob` exists but cannot
 * run inside the demo because it is gated on wall-clock UTC time and the
 * demo runs on a simulated trading-day clock. This job is the demo-side
 * trigger; nothing new is persisted or published from here.
 *
 * ## Idempotency
 *
 * Each call to [runOnce] is idempotent within the same simulated trading
 * day: for every book it first checks
 * `GET /api/v1/risk/sod-snapshot/{bookId}/status` and skips the
 * `POST /api/v1/risk/sod-snapshot/{bookId}` call when a baseline already
 * exists. Re-running on a fresh simulated day produces a new baseline.
 *
 * ## Failure isolation
 *
 * A failure for one book — whether on the status check or the snapshot
 * creation — is logged and swallowed so a single broken book does not
 * abort the whole sweep. This matches the pattern in [LimitSeedJob] and
 * [EodPromotionJob].
 *
 * @property client wire to `risk-orchestrator` HTTP routes.
 * @property books profiles to iterate per run. Defaults to the canonical
 *     eight seeded by `DevDataSeeder`.
 * @property clock pluggable clock — UTC in production, fixed in tests. The
 *     clock is only used to log the simulated trading day; the upstream
 *     SOD route uses its own `LocalDate.now()` for the baseline date.
 */
class SodBaselineCaptureJob(
    private val client: RiskOrchestratorClient,
    private val books: List<DemoBookProfile> = DemoBookProfiles.all(),
    private val clock: Clock = Clock.systemUTC(),
) {

    private val logger = LoggerFactory.getLogger(SodBaselineCaptureJob::class.java)

    /**
     * Performs a single sweep over every book in [books]. Returns the
     * number of books for which a new SOD baseline was captured on this
     * pass — books that already had a baseline today are not counted.
     */
    suspend fun runOnce(): Int {
        val tradingDay = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        logger.info(
            "Starting SodBaselineCaptureJob sweep over {} books for tradingDay={}",
            books.size,
            tradingDay,
        )
        var captured = 0
        for (profile in books) {
            if (captureForBook(profile.bookId, tradingDay)) {
                captured += 1
            }
        }
        logger.info(
            "Finished SodBaselineCaptureJob sweep — captured {}/{} baselines for tradingDay={}",
            captured,
            books.size,
            tradingDay,
        )
        return captured
    }

    private suspend fun captureForBook(bookId: String, tradingDay: LocalDate): Boolean {
        val existing = try {
            client.getSodBaselineStatus(bookId)
        } catch (failure: Exception) {
            logger.warn(
                "SOD status check failed for book {} on {} — skipping",
                bookId,
                tradingDay,
                failure,
            )
            return false
        }
        if (existing.exists) {
            logger.debug(
                "Book {} already has an SOD baseline for {} (baselineDate={}) — skipping",
                bookId,
                tradingDay,
                existing.baselineDate,
            )
            return false
        }

        return try {
            val captured = client.createSodSnapshot(bookId)
            logger.info(
                "Captured SOD baseline for book {} on {} (snapshotType={})",
                bookId,
                captured.baselineDate ?: tradingDay,
                captured.snapshotType,
            )
            true
        } catch (failure: Exception) {
            logger.warn(
                "SOD baseline capture failed for book {} on {} — continuing",
                bookId,
                tradingDay,
                failure,
            )
            false
        }
    }
}
