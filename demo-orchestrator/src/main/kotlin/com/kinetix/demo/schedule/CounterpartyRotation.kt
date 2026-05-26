package com.kinetix.demo.schedule

/**
 * Per-book in-memory round-robin counterparty rotation for the simulated
 * trader (kx-i72).
 *
 * Each book maintains its own monotonically-incrementing index into the
 * configured counterparty pool, so the i-th trade booked for a book attaches
 * the `i % counterparties.size`-th counterparty id. Over enough ticks, every
 * book sees every counterparty — which the UI Counterparty Exposure tile
 * relies on to render non-trivial concentration.
 *
 * The rotation is intentionally simple and deterministic: no shared global
 * counter, no probabilistic weighting, no time-of-day skew. Demos are easier
 * to reason about when "book X always pairs trade N with counterparty Y".
 *
 * This class is not thread-safe; callers should serialise access per book.
 * The simulator processes books sequentially inside a single coroutine, so
 * that contract is trivially met.
 *
 * @property counterparties the ordered pool of counterparty ids to cycle
 *     through. Defaults to the canonical 6 G-SIB demo counterparties
 *     ([DEMO_COUNTERPARTY_IDS]).
 */
class CounterpartyRotation(
    private val counterparties: List<String> = DEMO_COUNTERPARTY_IDS,
) {
    init {
        require(counterparties.isNotEmpty()) { "CounterpartyRotation requires at least one counterparty" }
    }

    private val cursorByBook: MutableMap<String, Int> = mutableMapOf()

    /**
     * Returns the next counterparty id for [bookId] and advances the book's
     * cursor by one. The first call for any book returns
     * `counterparties[0]`.
     */
    fun next(bookId: String): String {
        val current = cursorByBook.getOrDefault(bookId, 0)
        val cp = counterparties[current % counterparties.size]
        cursorByBook[bookId] = current + 1
        return cp
    }

    /** Snapshot of the underlying pool for introspection in tests. */
    val pool: List<String> get() = counterparties.toList()

    companion object {
        /**
         * Canonical demo counterparty pool — the 6 G-SIB ids curated in
         * `common.demo.CounterpartyTiers.G_SIB_IDS` and seeded with PFE/CVA
         * fixtures by the risk-orchestrator DevDataSeeder. Defined here
         * (not pulled from `common`) so the demo orchestrator does not gain
         * a new module dependency for kx-i72.
         */
        val DEMO_COUNTERPARTY_IDS: List<String> = listOf(
            "CP-GS",
            "CP-JPM",
            "CP-BARC",
            "CP-DB",
            "CP-UBS",
            "CP-CITI",
        )
    }
}
