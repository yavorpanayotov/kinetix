package com.kinetix.audit.model

/**
 * Verifies the strict-monotonicity invariant on a sequence of audit events:
 *
 *   For every group sharing a non-null aggregate id (here, [AuditEvent.tradeId]),
 *   the per-group sequence of either explicit [AuditEvent.sequenceNumber]s or,
 *   when no explicit sequence is recorded, auto-increment `id`s must be
 *   strictly increasing in arrival order.
 *
 * Compliance reconstructs the audit trail by replaying events; out-of-order
 * arrivals on the same aggregate indicate a torn write, a clock skew on
 * replay, or a gap in the hash chain. The check is intentionally O(n) — it
 * scans the input list once, holding only the most recent value per aggregate.
 *
 * Returns the first offending pair as a [MonotonicResult.Violation], or
 * [MonotonicResult.Monotonic] when the invariant holds across the whole list.
 * Events with a null `tradeId` are skipped: governance events do not form
 * an aggregate stream of their own.
 *
 * When every event in a given aggregate carries a non-null [sequenceNumber],
 * the check uses those numbers; otherwise it falls back to `id`.
 */
fun verifyMonotonicByAggregate(events: List<AuditEvent>): MonotonicResult {
    if (events.isEmpty()) return MonotonicResult.Monotonic

    // Partition once to decide per-aggregate whether to compare by sequenceNumber
    // or by id. A group with mixed presence falls back to id (the only field
    // guaranteed to exist on every event).
    val byAggregate = events.filter { it.tradeId != null }.groupBy { it.tradeId!! }

    for ((aggregateId, group) in byAggregate) {
        val useSequence = group.all { it.sequenceNumber != null }
        val keyOf: (AuditEvent) -> Long =
            if (useSequence) { e -> e.sequenceNumber!! } else { e -> e.id }
        var previous: Long? = null
        for (event in group) {
            val current = keyOf(event)
            if (previous != null && current <= previous) {
                return MonotonicResult.Violation(
                    aggregateId = aggregateId,
                    previousId = previous,
                    currentId = current,
                )
            }
            previous = current
        }
    }
    return MonotonicResult.Monotonic
}

/** Outcome of [verifyMonotonicByAggregate]. */
sealed interface MonotonicResult {
    /** All aggregate streams are strictly increasing. */
    data object Monotonic : MonotonicResult

    /** First out-of-order pair encountered for some aggregate. */
    data class Violation(
        val aggregateId: String,
        val previousId: Long,
        val currentId: Long,
    ) : MonotonicResult
}
