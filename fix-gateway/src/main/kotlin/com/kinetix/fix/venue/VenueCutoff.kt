package com.kinetix.fix.venue

import java.time.LocalTime
import java.time.ZoneId

/**
 * Per-venue session-close information used by [VenueCutoffRegistry].
 *
 * Holidays are deferred to phase 2.5 (ADR-0035 §Open questions). Until then the
 * registry conservatively treats every weekday as a trading day; orders may be
 * expired on a market holiday, which is operationally acceptable — the venue
 * replies cancel-on-reopen and the trader either re-submits the next day.
 *
 * @property venue       Identifier (NYSE / NASDAQ / LSE / TSE / HKEX).
 * @property zone        Venue's local time zone.
 * @property dayOpen     Local time-of-day at which the regular session opens.
 * @property dayCutoff   Local time-of-day after which a DAY order is considered expired.
 * @property maxGtdDays  Venue's policy maximum for GTD horizon.
 */
data class VenueCutoff(
    val venue: String,
    val zone: ZoneId,
    val dayOpen: LocalTime,
    val dayCutoff: LocalTime,
    val maxGtdDays: Long,
)
