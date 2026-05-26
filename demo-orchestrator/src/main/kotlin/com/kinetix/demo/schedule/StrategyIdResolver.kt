package com.kinetix.demo.schedule

/**
 * Resolves the set of strategies under which [SimulatedTraderJob] books
 * trades for a given demo book.
 *
 * `position-service` requires every trade to be linked to an already-persisted
 * strategy (`POST /api/v1/books/{bookId}/strategies/{strategyId}/trades`
 * returns 404 otherwise). A real fund books trades across multiple
 * sub-strategies per book — `equity-growth` is split into `core` and
 * `satellite`, `derivatives-book` into `vol-arb`/`directional`/`hedge`, and
 * so on. The resolver returns all known strategies for [bookId] so the
 * simulator can distribute trades across them uniformly at random.
 *
 * Pulling the lookup behind an interface lets tests inject a deterministic
 * fake while production code uses an HTTP-backed implementation that calls
 * `GET /api/v1/books/{bookId}/strategies` and caches the result per book.
 */
interface StrategyIdResolver {
    /**
     * Strategies seeded for [bookId]. The list is guaranteed non-empty —
     * implementations fall back to a synthesized id when the upstream API
     * returns nothing so the caller never needs to handle "no strategies"
     * separately.
     */
    suspend fun strategiesFor(bookId: String): List<String>
}
