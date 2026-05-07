package com.kinetix.position.fix

/**
 * Outcome of a cancel attempt; persisted via [CancelAttemptRecorder] so the
 * ghost-fill alerter can detect EXPIRED orders that received fills despite a
 * failed cancel.
 */
enum class CancelAttemptStatus {
    ACCEPTED,
    SESSION_DOWN,
    UNKNOWN_VENUE,
    INVALID_REQUEST,
    /** gRPC transport-level failure (DEADLINE_EXCEEDED, UNAVAILABLE, mTLS handshake, …). */
    RPC_FAILED,
}
