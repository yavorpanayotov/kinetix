package com.kinetix.demo.schedule

import com.kinetix.common.demo.CounterpartyTiers

/**
 * Per-book in-memory round-robin counterparty rotation for the simulated
 * trader (kx-i72, widened in kx-6o89).
 *
 * Each book maintains its own monotonically-incrementing index into the
 * configured counterparty pool, so the i-th trade booked for a book attaches
 * the `i % counterparties.size`-th counterparty id. Over enough ticks, every
 * book sees every counterparty — which the UI Counterparty Exposure tile
 * relies on to render non-trivial concentration.
 *
 * ## Why the default pool is the *full* universe (kx-6o89)
 *
 * Originally the default pool was just the 6 canonical G-SIBs. Reference data
 * (and the risk-orchestrator exposure seed) ship 30 counterparties — mid-tier
 * banks, CCPs, buy-side funds and corporates — but only the 6 G-SIBs ever saw a
 * simulated trade, so the other 24 rendered as `$0.00 / $0.00 / —` in the
 * Counterparty Risk tab. The default pool is now
 * [CounterpartyTiers.ALL_IDS] so exposure distributes across the whole book.
 *
 * ## Eligibility (credit-risk plausibility)
 *
 * A naive round-robin over all 30 would pair, say, a buy-side fund with a cash
 * equity or a CCP with an OTC swap — which a credit-risk buyer would flag
 * immediately. [next] therefore takes the trade's `instrumentType` and rotates
 * only within [CounterpartyTiers.eligibleFor] (intersected with the configured
 * pool): listed/cleared derivatives → banks + CCPs; OTC → banks + buy-side +
 * corporates; cash/govt/FX-spot → banks only. Each `(book, eligibility-subset)`
 * pair keeps an independent cursor, so each subset cycles in order.
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
 *     through. Defaults to the full demo counterparty universe
 *     ([CounterpartyTiers.ALL_IDS]).
 */
class CounterpartyRotation(
    private val counterparties: List<String> = CounterpartyTiers.ALL_IDS,
) {
    init {
        require(counterparties.isNotEmpty()) { "CounterpartyRotation requires at least one counterparty" }
    }

    private val cursorByKey: MutableMap<String, Int> = mutableMapOf()

    /**
     * Returns the next counterparty id for [bookId] and advances the book's
     * cursor by one, rotating over the *entire* configured pool. The first
     * call for any book returns `counterparties[0]`.
     *
     * Prefer the [next] overload that takes an `instrumentType` so the chosen
     * counterparty is plausible for the traded instrument.
     */
    fun next(bookId: String): String = nextFromSubset(bookId, counterparties)

    /**
     * Returns the next counterparty id for [bookId] that is *eligible* for
     * [instrumentType], advancing an independent cursor per
     * `(book, eligibility-subset)`.
     *
     * The eligible subset is [CounterpartyTiers.eligibleFor] intersected with
     * the configured pool, preserving the pool's order. If that intersection is
     * empty (e.g. a custom pool that shares no ids with the matched tier), the
     * method falls back to rotating over the full pool so a counterparty is
     * always returned.
     */
    fun next(bookId: String, instrumentType: String): String {
        val eligible = CounterpartyTiers.eligibleFor(instrumentType).toSet()
        val subset = counterparties.filter { it in eligible }
        val effective = subset.ifEmpty { counterparties }
        // Key the cursor on the subset identity so each distinct eligibility
        // group rotates independently within a book.
        val key = "$bookId|${effective.joinToString(",")}"
        return nextFromSubset(key, effective)
    }

    private fun nextFromSubset(cursorKey: String, subset: List<String>): String {
        val current = cursorByKey.getOrDefault(cursorKey, 0)
        val cp = subset[current % subset.size]
        cursorByKey[cursorKey] = current + 1
        return cp
    }

    /** Snapshot of the underlying pool for introspection in tests. */
    val pool: List<String> get() = counterparties.toList()
}
