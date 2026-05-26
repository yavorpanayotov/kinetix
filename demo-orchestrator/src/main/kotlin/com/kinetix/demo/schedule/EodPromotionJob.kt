package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.profile.DemoBookProfile
import com.kinetix.demo.profile.DemoBookProfiles
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Drives end-of-day promotion for every demo book once per simulated
 * trading day.
 *
 * The job is the demo equivalent of `risk-orchestrator`'s
 * `ScheduledAutoCloseJob`: at the configured close time it triggers a fresh
 * VaR snapshot per book and promotes the resulting valuation job to the
 * `OFFICIAL_EOD` `RunLabel`. Upstream `risk-orchestrator` owns the
 * `risk.official-eod` Kafka topic and the `OfficialEodDesignationsTable`
 * persistence write, so this job purely orchestrates the existing HTTP
 * surface — no new topic, table, or contract.
 *
 * The wider `risk-orchestrator` auto-close job exists but cannot run inside
 * the demo because it is gated on wall-clock UTC time and the demo runs on
 * a simulated trading-day clock. This job is the demo-side trigger.
 *
 * ## Idempotency
 *
 * Each call to [runOnce] is idempotent within the same simulated trading
 * day: for every book it first checks
 * `GET /api/v1/risk/jobs/{bookId}/official-eod?date=...` and skips the
 * VaR + promotion path when a designation already exists. Re-running on a
 * fresh simulated day produces a new designation.
 *
 * ## Failure isolation
 *
 * A failure for one book — whether on the idempotency check, the VaR call,
 * the job lookup, or the promotion — is logged and swallowed so a single
 * broken book does not abort the whole sweep. This matches the pattern in
 * [LimitSeedJob] and [EodCycleObserverJob].
 *
 * @property client wire to `risk-orchestrator` HTTP routes.
 * @property books profiles to iterate per run. Defaults to the canonical
 *     eight seeded by `DevDataSeeder`.
 * @property clock pluggable clock — UTC in production, fixed in tests.
 * @property promotedBy identifier recorded on the promoted job. The risk
 *     orchestrator rejects self-promotion (`triggeredBy == promotedBy`); the
 *     VaR API call sets `triggeredBy="API"` so `"demo-orchestrator"` is safe.
 */
class EodPromotionJob(
    private val client: RiskOrchestratorClient,
    private val books: List<DemoBookProfile> = DemoBookProfiles.all(),
    private val clock: Clock = Clock.systemUTC(),
    private val promotedBy: String = "demo-orchestrator",
) {

    private val logger = LoggerFactory.getLogger(EodPromotionJob::class.java)

    /**
     * Number of books the next [runOnce] sweep will iterate over. Exposed so
     * callers like the `POST /demo/trigger-eod` route can advertise the
     * sweep size in their response without re-deriving the seed list.
     */
    val bookCount: Int get() = books.size

    /**
     * Performs a single sweep over every book in [books]. Returns the
     * number of books for which a new EOD designation was promoted on this
     * pass — books already promoted today are not counted.
     */
    suspend fun runOnce(): Int {
        val valuationDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        logger.info(
            "Starting EodPromotionJob sweep over {} books for valuationDate={}",
            books.size,
            valuationDate,
        )
        var promoted = 0
        for (profile in books) {
            if (promoteForBook(profile.bookId, valuationDate)) {
                promoted += 1
            }
        }
        logger.info(
            "Finished EodPromotionJob sweep — promoted {}/{} books for valuationDate={}",
            promoted,
            books.size,
            valuationDate,
        )
        return promoted
    }

    private suspend fun promoteForBook(bookId: String, valuationDate: LocalDate): Boolean {
        val existing = try {
            client.findOfficialEod(bookId, valuationDate)
        } catch (failure: Exception) {
            logger.warn(
                "EOD idempotency check failed for book {} on {} — skipping",
                bookId,
                valuationDate,
                failure,
            )
            return false
        }
        if (existing != null) {
            logger.debug(
                "Book {} already has an Official EOD designation for {} (jobId={}) — skipping",
                bookId,
                valuationDate,
                existing.jobId,
            )
            return false
        }

        try {
            client.calculateVaR(bookId)
        } catch (failure: Exception) {
            logger.warn(
                "VaR calculation failed for book {} on {} — skipping EOD promotion",
                bookId,
                valuationDate,
                failure,
            )
            return false
        }

        val latestJob = try {
            client.findLatestCompletedJob(bookId)
        } catch (failure: Exception) {
            logger.warn(
                "Failed to read latest job for book {} on {} — skipping EOD promotion",
                bookId,
                valuationDate,
                failure,
            )
            return false
        }
        if (latestJob == null) {
            logger.warn(
                "No valuation job recorded for book {} on {} after VaR calculation — skipping promotion",
                bookId,
                valuationDate,
            )
            return false
        }

        return try {
            val promoted = client.promoteJobToOfficialEod(latestJob.jobId, promotedBy)
            logger.info(
                "Promoted job {} for book {} to OFFICIAL_EOD on {}",
                promoted.jobId,
                bookId,
                promoted.valuationDate,
            )
            true
        } catch (failure: Exception) {
            logger.warn(
                "Promotion to OFFICIAL_EOD failed for book {} (jobId={}) on {} — continuing",
                bookId,
                latestJob.jobId,
                valuationDate,
                failure,
            )
            false
        }
    }
}
