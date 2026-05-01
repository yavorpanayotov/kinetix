// NOTE: This package (com.kinetix.position.fix) is intended for future extraction
// into a standalone fix-gateway service per ADR-0035.

package com.kinetix.position.fix

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Resolves whether a venue's regular trading session has closed for the day,
 * driving the DAY-order auto-expiry logic in `ScheduledOrderExpirySweeper`.
 *
 * Launch venues per ADR-0035: NYSE, NASDAQ, LSE, TSE, HKEX. Cutoffs reflect each
 * venue's regular-session close (no half-days, no holidays — see [VenueCutoff]
 * comment). Unknown venues fall back to NYSE behaviour, which is the conservative
 * choice for US-listed instruments routed without an explicit venue.
 *
 * The registry is intentionally a value object holding immutable static data; tests
 * instantiate it with a custom map to simulate alternative venue policies.
 */
class VenueCutoffRegistry(
    private val cutoffs: Map<String, VenueCutoff> = DEFAULT,
    private val fallbackVenue: String = "NYSE",
) {

    /**
     * Returns true when [now] is past the venue's regular-session-close cutoff,
     * meaning a DAY order at this venue should be transitioned to EXPIRED.
     *
     * Weekends are always considered closed: any DAY order outstanding on a Saturday
     * or Sunday will be expired. This matches industry behaviour and avoids leaving
     * stale day-orders open across the weekend roll.
     */
    fun isSessionClosed(venue: String, now: Instant): Boolean {
        val cutoff = lookup(venue)
        val nowAtVenue = ZonedDateTime.ofInstant(now, cutoff.zone)
        if (nowAtVenue.dayOfWeek == DayOfWeek.SATURDAY || nowAtVenue.dayOfWeek == DayOfWeek.SUNDAY) {
            return true
        }
        return !nowAtVenue.toLocalTime().isBefore(cutoff.dayCutoff)
    }

    /** Returns the venue's cutoff record, falling back to [fallbackVenue] for unknown venues. */
    fun lookup(venue: String): VenueCutoff =
        cutoffs[venue.uppercase()]
            ?: cutoffs[fallbackVenue]
            ?: error("Fallback venue '$fallbackVenue' missing from registry")

    /** Returns the venue's max-GTD horizon in days. */
    fun maxGtdDays(venue: String): Long = lookup(venue).maxGtdDays

    companion object {
        /**
         * Launch venues per ADR-0035. Cutoffs reflect each venue's regular-session
         * close in local time; max-GTD horizons follow venue policy (NYSE/NASDAQ
         * cap GTD at 90 days; LSE/TSE/HKEX capped similarly until policy data
         * arrives).
         */
        val DEFAULT: Map<String, VenueCutoff> = listOf(
            VenueCutoff(
                venue = "NYSE",
                zone = ZoneId.of("America/New_York"),
                dayCutoff = LocalTime.of(16, 0),
                maxGtdDays = 90,
            ),
            VenueCutoff(
                venue = "NASDAQ",
                zone = ZoneId.of("America/New_York"),
                dayCutoff = LocalTime.of(16, 0),
                maxGtdDays = 90,
            ),
            VenueCutoff(
                venue = "LSE",
                zone = ZoneId.of("Europe/London"),
                dayCutoff = LocalTime.of(16, 30),
                maxGtdDays = 90,
            ),
            VenueCutoff(
                venue = "TSE",
                zone = ZoneId.of("Asia/Tokyo"),
                dayCutoff = LocalTime.of(15, 0),
                maxGtdDays = 90,
            ),
            VenueCutoff(
                venue = "HKEX",
                zone = ZoneId.of("Asia/Hong_Kong"),
                dayCutoff = LocalTime.of(16, 0),
                maxGtdDays = 90,
            ),
        ).associateBy { it.venue }
    }
}
