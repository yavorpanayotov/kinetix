package com.kinetix.fix.venue

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Resolves whether a venue's regular trading session is open. fix-gateway is the
 * sole owner of this registry per ADR-0035 phase 2; position-service consumes
 * cutoff data via the `IsVenueOpen` gRPC RPC rather than holding a duplicate.
 *
 * The registry was previously located in `position-service/.../fix/`; the move
 * keeps venue-policy logic where the FIX session lifecycle lives. Holiday
 * calendars layer in at phase 2.5 (YAML loaders + future vendor feeds).
 *
 * Unknown venues fall back to NYSE behaviour, matching today's conservative
 * default for US-listed instruments routed without an explicit venue.
 */
class VenueCutoffRegistry(
    private val cutoffs: Map<String, VenueCutoff> = DEFAULT,
    private val fallbackVenue: String = "NYSE",
) {

    /**
     * True when [now] is before the venue's regular-session-close cutoff and
     * after its open. Weekends are always considered closed.
     */
    fun isOpen(venue: String, now: Instant): Boolean {
        val cutoff = lookup(venue)
        val nowAtVenue = ZonedDateTime.ofInstant(now, cutoff.zone)
        if (nowAtVenue.dayOfWeek == DayOfWeek.SATURDAY || nowAtVenue.dayOfWeek == DayOfWeek.SUNDAY) {
            return false
        }
        val tod = nowAtVenue.toLocalTime()
        return !tod.isBefore(cutoff.dayOpen) && tod.isBefore(cutoff.dayCutoff)
    }

    /** Inverse of [isOpen]; preserved so the sweeper port reads naturally. */
    fun isSessionClosed(venue: String, now: Instant): Boolean = !isOpen(venue, now)

    /**
     * Next session close after [from]. If [from] is before today's cutoff returns
     * today's cutoff; otherwise rolls forward to the next weekday cutoff.
     */
    fun nextClose(venue: String, from: Instant): Instant {
        val cutoff = lookup(venue)
        var candidate = ZonedDateTime.ofInstant(from, cutoff.zone)
            .with(cutoff.dayCutoff)
        while (
            candidate.dayOfWeek == DayOfWeek.SATURDAY ||
            candidate.dayOfWeek == DayOfWeek.SUNDAY ||
            !candidate.toInstant().isAfter(from)
        ) {
            candidate = candidate.plusDays(1).with(cutoff.dayCutoff)
        }
        return candidate.toInstant()
    }

    fun lookup(venue: String): VenueCutoff =
        cutoffs[venue.uppercase()]
            ?: cutoffs[fallbackVenue]
            ?: error("Fallback venue '$fallbackVenue' missing from registry")

    fun maxGtdDays(venue: String): Long = lookup(venue).maxGtdDays

    /** True when [venue] is registered (case-insensitive); used by `IsVenueOpen` to surface UNKNOWN_VENUE. */
    fun knows(venue: String): Boolean = cutoffs.containsKey(venue.uppercase())

    companion object {
        /**
         * Launch venues per ADR-0035. Cutoffs reflect each venue's regular-session
         * close in local time; max-GTD horizons follow venue policy until vendor
         * data arrives.
         */
        val DEFAULT: Map<String, VenueCutoff> = listOf(
            VenueCutoff(
                venue = "NYSE",
                zone = ZoneId.of("America/New_York"),
                dayOpen = LocalTime.of(9, 30),
                dayCutoff = LocalTime.of(16, 0),
                maxGtdDays = 90,
            ),
            VenueCutoff(
                venue = "NASDAQ",
                zone = ZoneId.of("America/New_York"),
                dayOpen = LocalTime.of(9, 30),
                dayCutoff = LocalTime.of(16, 0),
                maxGtdDays = 90,
            ),
            VenueCutoff(
                venue = "LSE",
                zone = ZoneId.of("Europe/London"),
                dayOpen = LocalTime.of(8, 0),
                dayCutoff = LocalTime.of(16, 30),
                maxGtdDays = 90,
            ),
            VenueCutoff(
                venue = "TSE",
                zone = ZoneId.of("Asia/Tokyo"),
                dayOpen = LocalTime.of(9, 0),
                dayCutoff = LocalTime.of(15, 0),
                maxGtdDays = 90,
            ),
            VenueCutoff(
                venue = "HKEX",
                zone = ZoneId.of("Asia/Hong_Kong"),
                dayOpen = LocalTime.of(9, 30),
                dayCutoff = LocalTime.of(16, 0),
                maxGtdDays = 90,
            ),
        ).associateBy { it.venue }
    }
}
