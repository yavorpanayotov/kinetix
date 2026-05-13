package com.kinetix.common.demo

/**
 * Single source of truth for the demo trader roster. Consumed by:
 *
 *  * reference-data-service `DevDataSeeder` — seeds the `traders` table.
 *  * position-service `TradeTapeGenerator` / `DevDataSeeder` — tags every
 *    generated trade with a real traderId so the audit trail, the trade
 *    blotter, and per-trader limit / P&L views have something to render.
 *
 * Each book maps to a primary desk; each desk has 3–5 traders. Trader IDs
 * stay stable across services so the audit hash chain and the blotter agree
 * on identity.
 */
object DemoTraderRoster {

    /** Book id (owned by position-service) -> desk id (owned by reference-data-service). */
    val BOOK_TO_DESK: Map<String, String> = mapOf(
        "equity-growth" to "equity-growth",
        "tech-momentum" to "tech-momentum",
        "emerging-markets" to "emerging-markets",
        "fixed-income" to "rates-trading",
        "multi-asset" to "multi-asset-strategies",
        "macro-hedge" to "macro-hedge",
        "balanced-income" to "balanced-income",
        "derivatives-book" to "derivatives-trading",
        // Scenario books — fold into the closest desk so tape tagging still
        // resolves a trader. equity-ls / stress lean on the existing desks.
        "equity-ls-long" to "tech-momentum",
        "equity-ls-short" to "equity-growth",
        "stress-healthy-1" to "equity-growth",
        "stress-healthy-2" to "tech-momentum",
        "stress-breach" to "macro-hedge",
        "options-vol-1" to "derivatives-trading",
        "options-vol-2" to "derivatives-trading",
    )

    /** Desk id -> ordered list of trader ids. First entry is the desk's senior trader. */
    val TRADERS_BY_DESK: Map<String, List<String>> = mapOf(
        "equity-growth" to listOf("tr-eg-001", "tr-eg-002", "tr-eg-003", "tr-eg-004"),
        "tech-momentum" to listOf("tr-tm-001", "tr-tm-002", "tr-tm-003", "tr-tm-004"),
        "emerging-markets" to listOf("tr-em-001", "tr-em-002", "tr-em-003"),
        "rates-trading" to listOf("tr-rt-001", "tr-rt-002", "tr-rt-003", "tr-rt-004", "tr-rt-005"),
        "multi-asset-strategies" to listOf("tr-ma-001", "tr-ma-002", "tr-ma-003", "tr-ma-004"),
        "macro-hedge" to listOf("tr-mh-001", "tr-mh-002", "tr-mh-003"),
        "balanced-income" to listOf("tr-bi-001", "tr-bi-002", "tr-bi-003"),
        "derivatives-trading" to listOf("tr-dt-001", "tr-dt-002", "tr-dt-003", "tr-dt-004", "tr-dt-005"),
    )

    /** All trader ids known to the demo. */
    val ALL_TRADER_IDS: Set<String> = TRADERS_BY_DESK.values.flatten().toSet()

    /** Default (senior) trader for a book; null if the book is not in the roster. */
    fun primaryTraderFor(bookId: String): String? =
        BOOK_TO_DESK[bookId]?.let { TRADERS_BY_DESK[it]?.firstOrNull() }

    /**
     * Deterministic trader pick for a tape ticket. The (bookId, tradeId) key
     * collapses to a stable hash, so re-running the seeder produces the same
     * trader assignment — keeping the audit-hash CI guard happy.
     */
    fun traderForTicket(bookId: String, tradeId: String): String? {
        val deskId = BOOK_TO_DESK[bookId] ?: return null
        val traders = TRADERS_BY_DESK[deskId] ?: return null
        if (traders.isEmpty()) return null
        val hash = (bookId + "|" + tradeId).hashCode()
        return traders[Math.floorMod(hash, traders.size)]
    }
}
