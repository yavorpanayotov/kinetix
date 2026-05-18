package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorClient
import com.kinetix.demo.client.dtos.BacktestRequest
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate

/**
 * [BacktestInputProvider] backed by the risk-orchestrator
 * `GET /api/v1/risk/eod-timeline/{bookId}` endpoint.
 *
 * Pulls the last [windowDays] of official EOD entries for the book and pairs
 * consecutive entries into `(VaR prediction, realised P&L)` samples:
 *
 *   - `prediction_t = e_{t-1}.varValue` (predicted at the close of day t-1)
 *   - `pnl_t = e_t.pvValue - e_{t-1}.pvValue` (realised on day t)
 *
 * Pairs are skipped when any of `prev.varValue`, `prev.pvValue`, `cur.pvValue`
 * is null — partial promotions or backfill gaps should not pollute the
 * Kupiec/Christoffersen statistics.
 *
 * If fewer than [MIN_PAIRED_SAMPLES] valid samples remain after filtering, or
 * the upstream call fails outright, the provider falls back to [fallback] (the
 * deterministic [StubBacktestInputProvider] by default) so the demo flow still
 * produces a regulatory submission rather than degrading silently.
 */
class RiskOrchestratorBacktestInputProvider(
    private val client: RiskOrchestratorClient,
    private val fallback: BacktestInputProvider = StubBacktestInputProvider(),
    private val windowDays: Long = DEFAULT_WINDOW_DAYS,
    private val clock: Clock = Clock.systemUTC(),
) : BacktestInputProvider {

    override suspend fun fetchFor(bookId: String): BacktestRequest {
        val today = LocalDate.now(clock)
        val from = today.minusDays(windowDays)

        val timeline = try {
            client.eodTimeline(bookId, from, today)
        } catch (t: Throwable) {
            logger.warn("eod-timeline fetch failed for {}; using stub", bookId, t)
            return fallback.fetchFor(bookId)
        }

        val predictions = mutableListOf<Double>()
        val pnls = mutableListOf<Double>()
        val entries = timeline.entries
        for (i in 1 until entries.size) {
            val prev = entries[i - 1]
            val cur = entries[i]
            val v = prev.varValue ?: continue
            val pvPrev = prev.pvValue ?: continue
            val pvCur = cur.pvValue ?: continue
            predictions.add(v)
            pnls.add(pvCur - pvPrev)
        }

        if (predictions.size < MIN_PAIRED_SAMPLES) {
            logger.warn(
                "only {} paired samples for {}; using stub",
                predictions.size,
                bookId,
            )
            return fallback.fetchFor(bookId)
        }

        return BacktestRequest(
            dailyVarPredictions = predictions,
            dailyPnl = pnls,
            confidenceLevel = 0.99,
            calculationType = "PARAMETRIC",
        )
    }

    companion object {
        /** 31 entries yield up to 30 paired samples — matches the stub window. */
        const val DEFAULT_WINDOW_DAYS: Long = 31L

        /** Minimum paired samples needed to skip the fallback. */
        const val MIN_PAIRED_SAMPLES: Int = 5

        private val logger =
            LoggerFactory.getLogger(RiskOrchestratorBacktestInputProvider::class.java)
    }
}
