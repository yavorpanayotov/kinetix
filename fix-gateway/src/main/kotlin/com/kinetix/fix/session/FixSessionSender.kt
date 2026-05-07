package com.kinetix.fix.session

import quickfix.Message

/**
 * Abstraction over QuickFIX/J's `Session.sendToTarget(...)`. fix-gateway code
 * that constructs FIX messages depends on this interface so:
 *   - tests can substitute a fake that records messages without booting an Initiator,
 *   - the production path lives behind one well-defined seam,
 *   - dev-mode (`FIX_GATEWAY_LIVE_SESSIONS=false`) can swap in a no-op that returns
 *     [SendOutcome.SessionDown] without requiring real venue connectivity.
 */
interface FixSessionSender {
    /** Submit [message] on the session for [venue]. */
    fun send(venue: String, message: Message): SendOutcome
}

sealed class SendOutcome {
    /** The QuickFIX/J Session accepted the message for delivery. */
    object Sent : SendOutcome()

    /** No active session — caller surfaces SESSION_DOWN to the gRPC client. */
    object SessionDown : SendOutcome()

    /** Venue is unknown to the session manager. */
    object UnknownVenue : SendOutcome()
}
