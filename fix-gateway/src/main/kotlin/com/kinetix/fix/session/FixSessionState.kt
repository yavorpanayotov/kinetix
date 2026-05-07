package com.kinetix.fix.session

import java.time.Instant

/**
 * Per-venue FIX session sequence state. One row per venue in the
 * `fix_session_state` table (V2 migration). Loaded into the QuickFIX/J
 * Initiator on boot so the venue and fix-gateway agree on next-expected
 * sender/target sequence numbers across restarts.
 *
 * @property venue          Normalised upper-case venue identifier.
 * @property senderSeqNum   Next outbound MsgSeqNum (>= 1).
 * @property targetSeqNum   Next inbound MsgSeqNum (>= 1).
 * @property lastLogonAt    UTC time of last successful Logon, or null if never.
 * @property lastLogoutAt   UTC time of last clean Logout, or null if never.
 */
data class FixSessionState(
    val venue: String,
    val senderSeqNum: Long,
    val targetSeqNum: Long,
    val lastLogonAt: Instant?,
    val lastLogoutAt: Instant?,
) {
    init {
        // venue is invariant; seq-num invariants are enforced by the DB CHECK
        // and validated in FixSessionManager so corrupt rows can be loaded
        // and surfaced as CorruptSessionStateException rather than swallowed
        // at the data-access boundary.
        require(venue.isNotBlank()) { "venue must be non-blank" }
    }

    companion object {
        /** Cold-start defaults; FIX sessions start at MsgSeqNum=1. */
        fun coldStart(venue: String): FixSessionState =
            FixSessionState(venue = venue, senderSeqNum = 1, targetSeqNum = 1, lastLogonAt = null, lastLogoutAt = null)
    }
}
