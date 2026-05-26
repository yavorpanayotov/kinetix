package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.profile.DemoBookProfiles
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fires once on startup to seed per-book VaR for the 8 demo books so the
 * Risk / P&L / EOD / Reports tabs show real figures rather than $0.00 from
 * the moment the demo environment is brought up.
 *
 * ## Design
 *
 * The job is deliberately separate from [EodPromotionJob]. EOD promotion is
 * a daily, trading-hours-end concern; this job runs at startup and uses a
 * longer horizon (10-day, configurable) that is more representative for the
 * demo VaR display. The two jobs share [RiskOrchestratorClient] but are
 * otherwise independent.
 *
 * ## Idempotency
 *
 * Calling [runOnce] multiple times is safe. Each call re-triggers VaR for
 * every book; risk-orchestrator records a new valuation job each time, which
 * is the intended behaviour — the demo always shows the latest snapshot.
 *
 * ## Failure isolation
 *
 * - One failing book never blocks the rest. All failures are collected in the
 *   returned [BootstrapResult.failedBooks] and logged.
 * - `ConnectException` and HTTP 5xx are treated as transient: up to
 *   [maxRetries] attempts with exponential backoff (base [retryDelayMillis]).
 * - HTTP 400 with body containing "Cannot calculate VaR on empty positions
 *   list" is terminal — the book has no positions, retrying is pointless.
 *
 * ## Configuration
 *
 * VaR parameters are read from env vars at construction time so a single
 * instance uses consistent settings across all books:
 *
 * - `DEMO_BOOTSTRAP_VAR_CONFIDENCE` — wire string sent to risk-orchestrator,
 *   e.g. `"CL_95"` (default).
 * - `DEMO_BOOTSTRAP_VAR_HORIZON` — time horizon in days, e.g. `"10"` (default).
 *
 * @property riskOrchestratorClient wire to `risk-orchestrator` HTTP routes.
 * @property bookProvider supplies the set of book IDs to sweep. Defaults to
 *     the 8 books defined in [com.kinetix.demo.profile.DemoBookProfiles].
 * @property clock pluggable clock — UTC in production, fixed in tests.
 * @property retryDelayMillis base delay for exponential backoff. Set to 0 in
 *     tests to avoid sleeping.
 * @property maxRetries maximum number of attempts per book before giving up on
 *     transient failures.
 */
class DemoVaRBootstrapJob(
    private val riskOrchestratorClient: RiskOrchestratorClient,
    private val bookProvider: () -> Set<String> = {
        DemoBookProfiles.all().map { it.bookId }.toSet()
    },
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = 500L,
    private val maxRetries: Int = 3,
) {
    private val logger = LoggerFactory.getLogger(DemoVaRBootstrapJob::class.java)

    private val confidenceLevel: String =
        System.getenv("DEMO_BOOTSTRAP_VAR_CONFIDENCE") ?: "CL_95"

    private val horizonDays: Int =
        System.getenv("DEMO_BOOTSTRAP_VAR_HORIZON")?.toIntOrNull() ?: 10

    private val method: String = "PARAMETRIC"

    /**
     * Runs a single sweep over every book returned by [bookProvider], triggering
     * VaR calculation for each. Returns a [BootstrapResult] summarising successes,
     * failures, and the wall-clock duration of the sweep.
     */
    suspend fun runOnce(): BootstrapResult {
        val books = bookProvider()
        val valuationDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val startMillis = clock.millis()

        logger.info(
            "Starting DemoVaRBootstrapJob sweep over {} books for valuationDate={} " +
                "confidenceLevel={} horizonDays={} method={}",
            books.size, valuationDate, confidenceLevel, horizonDays, method,
        )

        val failedBooks = mutableListOf<String>()

        for (bookId in books) {
            val success = calculateWithRetry(bookId, valuationDate)
            if (!success) {
                failedBooks += bookId
            }
        }

        val durationMillis = clock.millis() - startMillis
        val successCount = books.size - failedBooks.size

        logger.info(
            "Finished DemoVaRBootstrapJob sweep — {}/{} books succeeded, {} failed in {}ms",
            successCount, books.size, failedBooks.size, durationMillis,
        )

        return BootstrapResult(
            successCount = successCount,
            failureCount = failedBooks.size,
            failedBooks = failedBooks.toList(),
            durationMillis = durationMillis,
        )
    }

    /**
     * Attempts VaR calculation for [bookId], retrying on transient errors.
     * Returns `true` on success, `false` when all retries are exhausted or a
     * terminal failure is encountered.
     */
    private suspend fun calculateWithRetry(bookId: String, valuationDate: LocalDate): Boolean {
        var attempt = 0
        while (attempt < maxRetries) {
            attempt++
            try {
                riskOrchestratorClient.calculateVaRWithParams(
                    bookId = bookId,
                    confidenceLevel = confidenceLevel,
                    horizonDays = horizonDays,
                    method = method,
                    valuationDate = valuationDate,
                )
                if (attempt > 1) {
                    logger.info(
                        "VaR bootstrap succeeded for book {} on attempt {}/{}",
                        bookId, attempt, maxRetries,
                    )
                }
                return true
            } catch (ex: Exception) {
                if (isEmptyPositionsFailure(ex)) {
                    logger.warn(
                        "Book {} has no positions — skipping VaR bootstrap (EMPTY)",
                        bookId,
                    )
                    return false
                }

                if (!isRetryable(ex)) {
                    logger.warn(
                        "Non-retryable failure for book {} on attempt {}/{} — skipping",
                        bookId, attempt, maxRetries, ex,
                    )
                    return false
                }

                if (attempt >= maxRetries) {
                    logger.warn(
                        "VaR bootstrap failed for book {} after {}/{} attempts — giving up",
                        bookId, attempt, maxRetries, ex,
                    )
                    return false
                }

                val backoffMs = retryDelayMillis * (1L shl (attempt - 1))
                logger.warn(
                    "Transient failure for book {} on attempt {}/{}, retrying in {}ms",
                    bookId, attempt, maxRetries, backoffMs, ex,
                )
                if (backoffMs > 0L) {
                    delay(backoffMs)
                }
            }
        }
        return false
    }

    private fun isEmptyPositionsFailure(ex: Exception): Boolean =
        ex.message?.contains("Cannot calculate VaR on empty positions list") == true

    private fun isRetryable(ex: Exception): Boolean =
        ex is ConnectException || isHttp5xxFailure(ex)

    private fun isHttp5xxFailure(ex: Exception): Boolean {
        val msg = ex.message ?: return false
        // risk-orchestrator HTTP client throws IllegalStateException with the
        // status code embedded: "risk-orchestrator POST ... returned 5xx: ..."
        return HTTP_5XX_PATTERN.containsMatchIn(msg)
    }

    private companion object {
        val HTTP_5XX_PATTERN = Regex("""returned 5\d{2}""")
    }
}
