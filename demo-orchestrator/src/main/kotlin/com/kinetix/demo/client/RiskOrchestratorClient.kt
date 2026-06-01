package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.BookExposureSnapshot
import com.kinetix.demo.client.dtos.EodPromotionResponseDto
import com.kinetix.demo.client.dtos.EodTimelineResponse
import com.kinetix.demo.client.dtos.SodBaselineStatusDto
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

    /**
     * Returns the SOD baseline status for [bookId] on the current trading day
     * via `GET /api/v1/risk/sod-snapshot/{bookId}/status`. Implementations
     * surface `exists=false` when no baseline has been captured. Used by the
     * SOD baseline capture scheduler for idempotency: re-running on the same
     * simulated day is a no-op.
     */
    suspend fun getSodBaselineStatus(bookId: String): SodBaselineStatusDto

    /**
     * Captures the start-of-day baseline for [bookId] via
     * `POST /api/v1/risk/sod-snapshot/{bookId}`. Upstream `risk-orchestrator`
     * owns the `SodBaselinesTable` and `DailyRiskSnapshotsTable` persistence
     * writes plus the optional pricing-Greek snapshot — this call simply
     * triggers them. Without a fresh baseline the P&L attribution endpoint
     * returns `412 Precondition Failed` and the UI shows the "No SOD baseline
     * for today" callout.
     */
    suspend fun createSodSnapshot(bookId: String): SodBaselineStatusDto

    /**
     * Triggers a parameterised VaR calculation for [bookId] via
     * `POST /api/v1/risk/var/{bookId}` using the supplied calculation
     * parameters.
     *
     * Used by [com.kinetix.demo.schedule.DemoVaRBootstrapJob] which needs
     * explicit control over confidence level and horizon so the bootstrap
     * snapshot is consistent regardless of risk-orchestrator defaults. The
     * existing zero-parameter [calculateVaR] retains the EOD defaults used
     * by [com.kinetix.demo.schedule.EodPromotionJob].
     *
     * @param bookId the book to calculate VaR for.
     * @param confidenceLevel wire string, e.g. `"CL_95"`.
     * @param horizonDays time horizon, e.g. `10`.
     * @param method calculation method string, e.g. `"PARAMETRIC"`.
     * @param valuationDate the as-of date for the calculation.
     */
    suspend fun calculateVaRWithParams(
        bookId: String,
        confidenceLevel: String,
        horizonDays: Int,
        method: String,
        valuationDate: java.time.LocalDate,
    )

    /**
     * Triggers a cross-book VaR aggregation for the supplied [bookIds] via
     * `POST /api/v1/risk/var/cross-book`, using [portfolioGroupId] as the
     * cache key in risk-orchestrator's `CrossBookVaRCache`.
     *
     * Used by [com.kinetix.demo.schedule.DemoVaRBootstrapJob] at the end of
     * the per-book sweep to seed the firm-level aggregate. Without this call
     * the cache key `"firm"` is never populated and
     * `GET /api/v1/risk/var/cross-book/firm` returns 404.
     *
     * @param bookIds the books to include in the aggregate.
     * @param portfolioGroupId the cache key, e.g. `"firm"`.
     * @param confidenceLevel wire string, e.g. `"CL_95"`.
     * @param horizonDays time horizon in days, e.g. `10`.
     * @param method calculation method string, e.g. `"PARAMETRIC"`.
     */
    suspend fun calculateCrossBookVaR(
        bookIds: List<String>,
        portfolioGroupId: String,
        confidenceLevel: String,
        horizonDays: Int,
        method: String,
    )

    /**
     * Fires a canned (pre-registered) stress scenario against [bookId] via
     * `POST /api/v1/risk/stress/{bookId}/canned/{scenarioName}` so the Risk
     * overview tile is populated. The delta-PV result is cached server-side
     * — this method intentionally discards the response body since the demo
     * orchestrator only needs the seed to have happened. The UI reads the
     * cached value via the matching `GET …/canned` endpoint.
     *
     * Used by [com.kinetix.demo.schedule.StressScenarioSeedJob] (issue
     * kx-wxy) on bootstrap and at SOD.
     */
    suspend fun runCannedStressScenario(bookId: String, scenarioName: String)

    /**
     * Fires a full multi-scenario stress sweep for [bookId] so the Scenarios
     * tab's comparison grid has a "latest run" to render on cold open
     * (issue kx-kjse).
     *
     * The implementation first lists the registered scenarios via
     * `GET /api/v1/risk/stress/scenarios`, then `POST`s them all to
     * `POST /api/v1/risk/stress/{bookId}/batch` — exactly what the UI's
     * "Run All Scenarios" button does. If no scenarios are registered the
     * batch is skipped (there is nothing to run). The response body is
     * discarded; the demo orchestrator only needs the sweep to have happened.
     *
     * Used by [com.kinetix.demo.schedule.StressScenarioSeedJob] on bootstrap
     * and at SOD.
     */
    suspend fun runAllStressScenarios(bookId: String)

    /**
     * Triggers a Key Rate Duration computation for [bookId] via
     * `GET /api/v1/risk/krd/{bookId}` so the KRD endpoint returns populated
     * `instruments`/`aggregated` for the book on the demo (issue kx-l8s7).
     *
     * risk-orchestrator computes KRD on demand from the book's fixed-income
     * (bond) positions; firing this at bootstrap warms the computation for the
     * rate-oriented demo books so the UI's KRD view is non-empty on first load.
     * The response body is discarded — the demo orchestrator only needs the
     * computation to have run against live positions.
     */
    suspend fun triggerKrdSnapshot(bookId: String)
}
