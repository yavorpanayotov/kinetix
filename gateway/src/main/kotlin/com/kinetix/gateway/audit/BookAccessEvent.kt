package com.kinetix.gateway.audit

import java.time.Instant

/**
 * Per-trader, per-book access record emitted by every gateway route
 * that handles privileged book data. Consumed by the audit-service so
 * a regulator can reconstruct "who else had access to BOOK-A last
 * week" without grepping load-balancer logs.
 *
 * DENIED outcomes are first-class: a denied attempt is just as
 * interesting as an allowed one for an insider-leak investigation.
 */
data class BookAccessEvent(
    val traderId: String,
    val bookId: String,
    val route: String,
    val outcome: BookAccessOutcome,
    val accessedAt: Instant,
) {
    fun toLogLine(): String = buildString {
        append("BookAccess ")
        append("trader=").append(traderId)
        append(" book=").append(bookId)
        append(" route=").append(route)
        append(" outcome=").append(outcome)
        append(" at=").append(accessedAt)
    }

    companion object {
        fun of(
            traderId: String,
            bookId: String,
            route: String,
            outcome: BookAccessOutcome,
            accessedAt: Instant,
        ): BookAccessEvent {
            require(traderId.isNotEmpty()) { "BookAccessEvent: traderId must not be empty" }
            require(bookId.isNotEmpty()) { "BookAccessEvent: bookId must not be empty" }
            return BookAccessEvent(traderId, bookId, route, outcome, accessedAt)
        }
    }
}

enum class BookAccessOutcome { ALLOWED, DENIED }
