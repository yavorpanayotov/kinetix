package com.kinetix.fix.session

import java.time.Instant

/**
 * Single row in `fix_message_log`. Carries the FIX message metadata needed for
 * replay, reconciliation, and the cancel-on-disconnect flow.
 *
 * @property venue        Normalised upper-case venue identifier.
 * @property direction    "IN" for inbound messages, "OUT" for outbound messages.
 * @property msgType      FIX tag 35 value, e.g. "D", "F", "8", "9".
 * @property rawMessage   Pipe-delimited FIX message string (SOH replaced with '|').
 * @property clOrdId      FIX tag 11 (ClOrdID), nullable for session-layer messages.
 * @property venueOrderId FIX tag 37 (OrderID assigned by venue), nullable before venue ack.
 * @property orderStatus  OPEN or TERMINAL; OPEN for 35=D until a terminal 35=8 closes it.
 * @property sentAt       UTC timestamp of send/receive.
 */
data class FixMessageLogEntry(
    val venue: String,
    val direction: String,
    val msgType: String,
    val rawMessage: String,
    val clOrdId: String?,
    val venueOrderId: String?,
    val orderStatus: String = "OPEN",
    val sentAt: Instant,
)
