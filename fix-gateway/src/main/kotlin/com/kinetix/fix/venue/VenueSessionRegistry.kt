package com.kinetix.fix.venue

/**
 * Maps a venue identifier to its FIX session configuration. Normalises venue
 * input (uppercase, whitespace-stripped) so `nyse `, `Nyse`, and `NYSE` all
 * resolve to the same session — defects-in-routing surface as `UNKNOWN_VENUE`
 * rather than silently picking the wrong session.
 *
 * Phase 2 launch venues per ADR-0035: NYSE, NASDAQ, LSE, TSE, HKEX.
 */
class VenueSessionRegistry(
    private val sessions: Map<String, VenueSession> = DEFAULT,
) {

    fun lookup(venue: String): VenueSession? = sessions[normalise(venue)]

    fun knows(venue: String): Boolean = sessions.containsKey(normalise(venue))

    fun all(): Collection<VenueSession> = sessions.values

    private fun normalise(venue: String): String = venue.trim().uppercase()

    companion object {
        /**
         * Launch venues. `defaultVenueAckTimeoutMs` follows the per-venue
         * latency profile from ADR-0035 phase 4: NYSE/NASDAQ co-lo 200ms, LSE
         * 500ms, TSE/HKEX 1s, EM brokers 5s. Phase 2 only consumes
         * sender/target comp ids; the timeout is wired in phase 4.
         */
        val DEFAULT: Map<String, VenueSession> = listOf(
            VenueSession("NYSE",   "FIX.4.4", "KINETIX", "NYSE",   200),
            VenueSession("NASDAQ", "FIX.4.4", "KINETIX", "NASDAQ", 200),
            VenueSession("LSE",    "FIX.4.4", "KINETIX", "LSE",    500),
            VenueSession("TSE",    "FIX.4.4", "KINETIX", "TSE",    1000),
            VenueSession("HKEX",   "FIX.4.4", "KINETIX", "HKEX",   1000),
        ).associateBy { it.venue }
    }
}
