package com.kinetix.gateway.websocket

import io.ktor.server.websocket.WebSocketServerSession

/**
 * A single `/ws/copilot` WebSocket subscriber together with the set of books
 * its end user is scoped to (PR 7 / ADR-0036).
 *
 * The book scope is derived from the connecting user's `X-User-Books` access
 * — sourced from the JWT `books` claim — at connect time (see
 * [com.kinetix.gateway.websocket.copilotWebSocket]). A subscriber receives an
 * intraday push only when the push's `book_id` falls inside [books]. A `null`
 * [books] denotes wildcard access (risk managers, compliance, admins): the
 * subscriber then receives every push regardless of book.
 */
data class CopilotSubscriber(
    val session: WebSocketServerSession,
    val books: Set<String>?,
) {
    /** True when this subscriber should receive a push scoped to [bookId]. */
    fun isScopedTo(bookId: String): Boolean = books == null || bookId in books
}
