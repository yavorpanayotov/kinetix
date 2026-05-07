package com.kinetix.fix.venue

/**
 * Per-venue FIX session configuration. Drives the QuickFIX/J `SessionID` lookup
 * inside [com.kinetix.fix.session.FixSessionManager] and surfaces the per-venue
 * defaults used by phase-4 [PlaceOrder] (e.g. `defaultVenueAckTimeoutMs`).
 *
 * @property venue                     Normalised upper-case identifier.
 * @property fixVersion                FIX protocol version, e.g. `FIX.4.2`, `FIX.4.4`.
 * @property senderCompId              SenderCompID we present to the venue.
 * @property targetCompId              TargetCompID expected from the venue.
 * @property defaultVenueAckTimeoutMs  Per-venue PENDING_NEW timeout (phase-4 use; included
 *                                     here so the registry stays stable across phases).
 */
data class VenueSession(
    val venue: String,
    val fixVersion: String,
    val senderCompId: String,
    val targetCompId: String,
    val defaultVenueAckTimeoutMs: Int,
)
