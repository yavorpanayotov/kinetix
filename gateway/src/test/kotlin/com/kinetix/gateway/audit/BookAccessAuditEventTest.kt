package com.kinetix.gateway.audit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

/**
 * Compliance needs an audit trail of who looked at what book and when.
 * Read-only access is still privileged data (positions disclose
 * strategy) and a regulator examining a trade-information leak will
 * ask "who else had access to that book the week before the leak?".
 * Every gateway route that takes a `bookId` therefore emits a
 * structured BookAccessEvent the audit-service consumes.
 */
class BookAccessAuditEventTest : FunSpec({

    val t0 = Instant.parse("2026-05-28T09:00:00Z")

    test("constructs an event with the trader, book, route, and timestamp") {
        val event = BookAccessEvent.of(
            traderId = "alice",
            bookId = "BOOK-A",
            route = "GET /risk/var",
            outcome = BookAccessOutcome.ALLOWED,
            accessedAt = t0,
        )
        event.traderId shouldBe "alice"
        event.bookId shouldBe "BOOK-A"
        event.route shouldBe "GET /risk/var"
        event.outcome shouldBe BookAccessOutcome.ALLOWED
        event.accessedAt shouldBe t0
    }

    test("DENIED outcomes are first-class (so they are not silently dropped)") {
        val event = BookAccessEvent.of(
            "bob", "BOOK-A", "GET /positions", BookAccessOutcome.DENIED, t0,
        )
        event.outcome shouldBe BookAccessOutcome.DENIED
    }

    test("toLogLine includes every field for compliance grep") {
        val event = BookAccessEvent.of(
            "alice", "BOOK-A", "GET /risk/var", BookAccessOutcome.ALLOWED, t0,
        )
        val line = event.toLogLine()
        line shouldContain "trader=alice"
        line shouldContain "book=BOOK-A"
        line shouldContain "route=GET /risk/var"
        line shouldContain "outcome=ALLOWED"
        line shouldContain t0.toString()
    }

    test("an empty trader id is rejected at construction (trail must name the actor)") {
        val ex = runCatching {
            BookAccessEvent.of("", "BOOK-A", "GET /positions", BookAccessOutcome.ALLOWED, t0)
        }
        ex.isFailure shouldBe true
    }

    test("an empty book id is rejected (no logging access to nothing)") {
        val ex = runCatching {
            BookAccessEvent.of("alice", "", "GET /positions", BookAccessOutcome.ALLOWED, t0)
        }
        ex.isFailure shouldBe true
    }
})
