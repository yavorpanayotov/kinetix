package com.kinetix.audit.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Audit events for a given aggregate (trade, book, model) must arrive in
 * a strictly increasing order — both the auto-increment `id` (insertion
 * order) and the explicit `sequenceNumber` (when the upstream populates
 * it) carry the linear order of state changes. Out-of-order or duplicated
 * sequence numbers indicate a torn write, a clock skew on replay, or a
 * gap in the hash chain — all of which break Compliance's ability to
 * reconstruct a deterministic trail from the trail itself.
 *
 * The acceptance criterion in beads kx-ryg6 is to encode that invariant
 * as a checkable, unit-testable function. `verifyMonotonicByAggregate`
 * groups events by `tradeId` (the canonical trade-event aggregate) and
 * returns `Monotonic` if every group's `id` sequence is strictly
 * increasing, or `Violation(aggregateId, previous, current)` for the
 * first offending pair. Sequence numbers (when present on every event
 * in a group) are checked the same way.
 */
class AuditEventSequenceMonotonicityTest : FunSpec({

    val t0 = Instant.parse("2026-05-28T08:00:00Z")

    fun event(
        id: Long,
        tradeId: String?,
        receivedAt: Instant = t0,
        sequenceNumber: Long? = null,
    ) = AuditEvent(
        id = id,
        tradeId = tradeId,
        receivedAt = receivedAt,
        sequenceNumber = sequenceNumber,
    )

    test("returns Monotonic for an empty event list") {
        verifyMonotonicByAggregate(emptyList()) shouldBe MonotonicResult.Monotonic
    }

    test("returns Monotonic for a single event") {
        verifyMonotonicByAggregate(listOf(event(1L, "T1"))) shouldBe MonotonicResult.Monotonic
    }

    test("returns Monotonic when ids are strictly increasing per aggregate") {
        val events = listOf(
            event(1L, "T1"),
            event(2L, "T1"),
            event(3L, "T1"),
        )
        verifyMonotonicByAggregate(events) shouldBe MonotonicResult.Monotonic
    }

    test("returns Monotonic when two aggregates interleave but each is increasing") {
        val events = listOf(
            event(1L, "T1"),
            event(2L, "T2"),
            event(3L, "T1"),
            event(4L, "T2"),
        )
        verifyMonotonicByAggregate(events) shouldBe MonotonicResult.Monotonic
    }

    test("flags a Violation when ids decrease for a single aggregate") {
        val events = listOf(
            event(5L, "T1"),
            event(3L, "T1"),
        )
        verifyMonotonicByAggregate(events) shouldBe
            MonotonicResult.Violation(aggregateId = "T1", previousId = 5L, currentId = 3L)
    }

    test("flags a Violation on duplicate ids within an aggregate (strictly, not weakly, increasing)") {
        val events = listOf(
            event(7L, "T1"),
            event(7L, "T1"),
        )
        verifyMonotonicByAggregate(events) shouldBe
            MonotonicResult.Violation(aggregateId = "T1", previousId = 7L, currentId = 7L)
    }

    test("ignores events with a null tradeId (governance events form no aggregate)") {
        val events = listOf(
            event(1L, null),
            event(2L, "T1"),
            event(3L, null),
            event(4L, "T1"),
        )
        verifyMonotonicByAggregate(events) shouldBe MonotonicResult.Monotonic
    }

    test("flags a Violation in sequence numbers when every event in an aggregate has one") {
        val events = listOf(
            event(1L, "T1", sequenceNumber = 10L),
            event(2L, "T1", sequenceNumber = 9L),
        )
        verifyMonotonicByAggregate(events) shouldBe
            MonotonicResult.Violation(aggregateId = "T1", previousId = 10L, currentId = 9L)
    }

    test("falls back to id when an aggregate has mixed-presence sequence numbers") {
        // Mixed: id ordering still applies, and ids are increasing — Monotonic.
        val events = listOf(
            event(1L, "T1", sequenceNumber = 10L),
            event(2L, "T1", sequenceNumber = null),
            event(3L, "T1", sequenceNumber = 12L),
        )
        verifyMonotonicByAggregate(events) shouldBe MonotonicResult.Monotonic
    }
})
