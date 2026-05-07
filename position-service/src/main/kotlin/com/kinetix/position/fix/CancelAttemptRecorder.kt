package com.kinetix.position.fix

import java.time.Instant

/**
 * Persists every cancel attempt outcome so the ghost-fill alerter
 * (FIXExecutionReportProcessor) can correlate EXPIRED orders that received
 * fills against failed-cancel attempts. Contract is fire-and-forget — failure
 * to record must not block the sweeper's state-side EXPIRED transition.
 */
fun interface CancelAttemptRecorder {
    fun record(
        orderId: String,
        venue: String,
        status: CancelAttemptStatus,
        attemptedAt: Instant,
        detail: String,
    )
}
