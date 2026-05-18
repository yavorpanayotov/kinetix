package com.kinetix.demo.schedule

/**
 * Resolves the strategy id under which [SimulatedTraderJob] books trades for a
 * given demo book.
 *
 * `position-service` requires every trade to be linked to an already-persisted
 * strategy (`POST /api/v1/books/{bookId}/strategies/{strategyId}/trades` returns
 * 404 otherwise), and strategy ids are NOT static across environments. Pulling
 * the lookup behind an interface lets tests inject a deterministic fake while
 * production code can swap in a config-driven or HTTP-backed implementation.
 */
interface StrategyIdResolver {
    /** Strategy id to use when booking trades against [bookId]. */
    fun strategyIdFor(bookId: String): String
}
