package com.kinetix.regulatory.model

/**
 * Market-volatility regime classification used by the scenario selector.
 *
 * The four levels capture the realised-volatility / realised-correlation
 * profile of the rolling 30-day window: NORMAL is the calm baseline,
 * ELEVATED matches taper-tantrum-style rates moves, STRESSED matches GFC
 * / COVID body of the distribution, and CRISIS reserves the tail-event
 * library (Volmageddon, SVB, LTCM) for moments when realised vol breaks
 * the 95th percentile.
 */
enum class MarketRegime {
    NORMAL,
    ELEVATED,
    STRESSED,
    CRISIS,
}
