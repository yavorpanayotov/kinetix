package com.kinetix.position.limit

import java.time.LocalTime

/** Tier of limit applicable at a given moment in the trading day. */
enum class LimitTier { INTRADAY, OVERNIGHT }

/**
 * Return the limit tier that applies at [time], given the exchange's
 * regular trading hours. INTRADAY applies for `[marketOpen, marketClose)`
 * (open inclusive, close exclusive); OVERNIGHT for everything else.
 *
 * Mis-tagging an after-close exposure as "intraday" lets a trader
 * carry an oversized overnight position the risk officer thinks
 * they've capped — the tier-flip at the close is the gate.
 */
fun applicableLimitTier(
    time: LocalTime,
    marketOpen: LocalTime,
    marketClose: LocalTime,
): LimitTier {
    val inMarket = !time.isBefore(marketOpen) && time.isBefore(marketClose)
    return if (inMarket) LimitTier.INTRADAY else LimitTier.OVERNIGHT
}
