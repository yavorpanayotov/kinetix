package com.kinetix.demo.schedule

import com.kinetix.demo.client.dtos.BacktestRequest

/**
 * Synthetic, deterministic implementation of [BacktestInputProvider] used by
 * the demo flow.
 *
 * Returns a 30-day window of VaR predictions and realised P&L drawn from
 * fixed-formula values so the regulatory-service receives a real request and
 * computes real Kupiec/Christoffersen statistics — without requiring a new
 * upstream API on `risk-orchestrator` to expose `daily_risk_snapshots`.
 *
 * VaR is flat at $1,000. P&L cycles through `{-500, -250, 0, 250, 500}` —
 * spreading positive and negative outcomes so the backtest produces a small
 * non-zero violation count rather than a degenerate all-pass / all-fail
 * outcome.
 *
 * `confidenceLevel = 0.99` and `calculationType = "PARAMETRIC"` mirror the
 * regulatory-service defaults.
 */
class StubBacktestInputProvider : BacktestInputProvider {

    override suspend fun fetchFor(bookId: String): BacktestRequest {
        val varSeries = List(WINDOW_DAYS) { 1_000.0 }
        val pnlSeries = List(WINDOW_DAYS) { i -> ((i % 5) - 2) * 250.0 }
        return BacktestRequest(
            dailyVarPredictions = varSeries,
            dailyPnl = pnlSeries,
            confidenceLevel = 0.99,
            calculationType = "PARAMETRIC",
        )
    }

    companion object {
        private const val WINDOW_DAYS = 30
    }
}
