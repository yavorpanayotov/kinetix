package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.BookExposureSnapshot
import com.kinetix.demo.client.dtos.EodPromotionResponseDto
import com.kinetix.demo.client.dtos.EodTimelineResponse
import com.kinetix.demo.client.dtos.ValuationJobSummary
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Contract over `risk-orchestrator` HTTP APIs that the demo orchestrator
 * relies on to size and seed limits each day.
 *
 * The interface keeps the demo decoupled from Ktor and from the upstream wire
 * shapes so tests can substitute an in-memory fake without spinning up an
 * HTTP server.
 */
interface RiskOrchestratorClient {

    /**
     * Reads the current risk exposure for the given book from
     * `GET /api/v1/risk/hierarchy/BOOK/{bookId}`.
     */
    suspend fun readBookExposure(bookId: String): BookExposureSnapshot

    /**
     * Seeds a limit for the given book at the supplied threshold.
     *
     * Implementations target whatever upstream route is responsible for
     * persisting the limit. The current production implementation falls back
     * to `POST /api/v1/risk/budgets` because risk-orchestrator does not yet
     * expose a dedicated per-book VaR/Delta limit route.
     */
    suspend fun seedLimit(bookId: String, limitType: LimitType, threshold: BigDecimal)

    /**
     * Reads the official end-of-day timeline for the given book over the
     * inclusive `[from, to]` window from `GET /api/v1/risk/eod-timeline/{bookId}`.
     *
     * Entries are returned ascending by valuation date. Consumers pair
     * consecutive entries to derive `(VaR prediction, realised P&L)` samples
     * for backtesting.
     */
    suspend fun eodTimeline(bookId: String, from: LocalDate, to: LocalDate): EodTimelineResponse

    /**
     * Triggers a fresh VaR calculation for [bookId] via
     * `POST /api/v1/risk/var/{bookId}`. The upstream service records a new
     * valuation job as a side effect; this method intentionally discards the
     * VaR result body since the only thing the demo flow needs is the
     * "job recorded" outcome — the job ID itself is retrieved separately
     * through [findLatestCompletedJob].
     */
    suspend fun calculateVaR(bookId: String)

    /**
     * Returns the most recent completed valuation job for [bookId] via
     * `GET /api/v1/risk/jobs/{bookId}?limit=1`, or `null` if no job has been
     * recorded yet. Used by the demo EOD scheduler to pick up the job
     * created by [calculateVaR] so it can be promoted.
     */
    suspend fun findLatestCompletedJob(bookId: String): ValuationJobSummary?

    /**
     * Returns the existing Official EOD designation for [bookId] on
     * [valuationDate] via `GET /api/v1/risk/jobs/{bookId}/official-eod`, or
     * `null` if no designation exists yet. Used by the demo EOD scheduler
     * for idempotency: a re-run on the same simulated day is a no-op.
     */
    suspend fun findOfficialEod(bookId: String, valuationDate: LocalDate): EodPromotionResponseDto?

    /**
     * Promotes the supplied [jobId] to `OFFICIAL_EOD` via
     * `PATCH /api/v1/risk/jobs/{jobId}/label`. Upstream `risk-orchestrator`
     * is the owner of the `risk.official-eod` Kafka topic and the
     * `OfficialEodDesignationsTable`; this call triggers both the event
     * publication and the persistence write that the
     * `GET /api/v1/risk/eod-timeline/{bookId}` read path observes.
     */
    suspend fun promoteJobToOfficialEod(jobId: String, promotedBy: String): EodPromotionResponseDto
}
