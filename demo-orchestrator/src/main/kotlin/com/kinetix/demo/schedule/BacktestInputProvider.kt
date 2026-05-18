package com.kinetix.demo.schedule

import com.kinetix.demo.client.dtos.BacktestRequest

/**
 * Supplies the [BacktestRequest] inputs for a given book on each EOD tick.
 *
 * `EodCycleObserverJob` calls this once per `OfficialEodPromotedEvent` to obtain
 * the last-30-days daily VaR predictions and realised P&L pairs that
 * `regulatory-service` needs for Kupiec/Christoffersen backtesting.
 *
 * The interface keeps the job decoupled from the data source: today an
 * in-memory deterministic stub ships the demo, but a later iteration can swap
 * in an HTTP- or DB-backed implementation without touching the job.
 */
interface BacktestInputProvider {
    /** Returns the backtest request payload to send for [bookId]. */
    suspend fun fetchFor(bookId: String): BacktestRequest
}
