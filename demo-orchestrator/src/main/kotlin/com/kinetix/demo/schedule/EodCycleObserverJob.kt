package com.kinetix.demo.schedule

import com.kinetix.demo.client.RegulatoryServiceClient
import com.kinetix.demo.client.dtos.CreateSubmissionRequest
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Observes the EOD promotion cycle by reacting to
 * [OfficialEodPromotedEvent]s published on the `risk.official-eod` Kafka topic
 * and fanning out the two regulatory follow-ups the demo flow requires:
 *
 *  1. `POST /api/v1/regulatory/backtest/{bookId}` — appends today's VaR vs
 *     realised P&L (may produce a Kupiec/Christoffersen exception).
 *  2. `POST /api/v1/submissions` — drafts a `DAILY_RISK_SUMMARY` row with a
 *     deadline of T+1 17:00 UTC.
 *
 * A 30s delay is interposed before the regulatory calls so the
 * materialized-view refresh and the counterparty-risk job have time to settle.
 *
 * The two regulatory calls are failure-isolated from each other: a failure of
 * the backtest call does not block the submission draft, and a failure of the
 * submission call does not propagate out of [processEvent]. The intent is to
 * keep the demo flowing even if one regulatory endpoint is transiently
 * unavailable — both failures are logged.
 *
 * The Kafka subscription / `RetryableConsumer` loop wiring lives in checkbox
 * 3.3 (Application.kt). This class exposes [processEvent] for direct
 * invocation so unit tests can drive the regulatory fan-out without spinning
 * up Kafka.
 *
 * @property regulatoryClient wire to `regulatory-service`.
 * @property backtestInputProvider supplies the last-30-day VaR / P&L series.
 * @property postEventDelay how long to wait after each event before calling
 *     the regulatory endpoints. Default 30s; tests override to `ZERO`.
 * @property clock kept for symmetry with other schedulers; the deadline is
 *     derived from the event's `valuationDate`, not from wall-clock.
 */
class EodCycleObserverJob(
    private val regulatoryClient: RegulatoryServiceClient,
    private val backtestInputProvider: BacktestInputProvider,
    private val postEventDelay: Duration = Duration.ofSeconds(30),
    @Suppress("unused") private val clock: Clock = Clock.systemUTC(),
) {

    suspend fun processEvent(event: OfficialEodPromotedEvent) {
        delay(postEventDelay.toMillis())

        try {
            val backtestRequest = backtestInputProvider.fetchFor(event.bookId)
            val backtestResult = regulatoryClient.runBacktest(event.bookId, backtestRequest)
            logger.info(
                "Backtest for {} complete: violations={} trafficLight={}",
                event.bookId, backtestResult.violationCount, backtestResult.trafficLightZone,
            )
        } catch (failure: Throwable) {
            logger.warn(
                "Backtest call failed for book {} on valuationDate {} — continuing to submission",
                event.bookId, event.valuationDate, failure,
            )
        }

        try {
            val deadline = nextDayAt17UtcIso(event.valuationDate)
            val submissionRequest = CreateSubmissionRequest(
                reportType = REPORT_TYPE,
                preparerId = PREPARER_ID,
                deadline = deadline,
            )
            val ref = regulatoryClient.createSubmission(submissionRequest)
            logger.info(
                "Submission draft created for book {}: id={} status={}",
                event.bookId, ref.id, ref.status,
            )
        } catch (failure: Throwable) {
            logger.warn(
                "Submission creation failed for book {} on valuationDate {}",
                event.bookId, event.valuationDate, failure,
            )
        }
    }

    private fun nextDayAt17UtcIso(valuationDate: String): String {
        val parsed = LocalDate.parse(valuationDate)
        val deadlineDate = parsed.plusDays(1)
        return ZonedDateTime.of(deadlineDate, LocalTime.of(17, 0), ZoneOffset.UTC)
            .toInstant()
            .toString()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(EodCycleObserverJob::class.java)
        private const val REPORT_TYPE = "DAILY_RISK_SUMMARY"
        private const val PREPARER_ID = "demo-orchestrator"
    }
}
