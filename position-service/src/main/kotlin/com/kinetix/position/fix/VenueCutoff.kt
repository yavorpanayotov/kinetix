// NOTE: This package (com.kinetix.position.fix) is intended for future extraction
// into a standalone fix-gateway service per ADR-0035.

package com.kinetix.position.fix

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Per-venue order-session-close information used by [VenueCutoffRegistry] to decide
 * whether a DAY order should be expired. The cutoff is expressed in the venue's local
 * time zone so daylight-saving transitions are handled implicitly.
 *
 * Holidays are not modelled in this baseline registry — a holiday calendar feed
 * (Bloomberg ECAL or vendor equivalent) is queued as a follow-on per ADR-0035 §Open
 * questions. Until then the sweeper conservatively treats every weekday as a trading
 * day, which means orders may be expired on a market holiday. That is operationally
 * acceptable: the trader either re-submits the next day or the cancel reaches a
 * non-trading session and is replied to as a cancel-on-reopen by the venue.
 *
 * @property venue       Identifier (NYSE / NASDAQ / LSE / TSE / HKEX).
 * @property zone        Venue's local time zone.
 * @property dayCutoff   Local time-of-day after which a DAY order is considered expired.
 * @property maxGtdDays  Venue's policy maximum for GTD horizon.
 */
data class VenueCutoff(
    val venue: String,
    val zone: ZoneId,
    val dayCutoff: LocalTime,
    val maxGtdDays: Long,
)
