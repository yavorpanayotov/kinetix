package com.kinetix.gateway.websocket

import com.auth0.jwk.JwkProvider
import com.kinetix.gateway.auth.JwtConfig
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*

/**
 * Registers the `/ws/copilot` WebSocket route — the subscriber side of the
 * intraday Copilot push channel (PR 7 / ADR-0036). Mirrors [alertWebSocket].
 *
 * On connect the route:
 *
 *  1. **Authenticates** the JWT supplied as the `?token=` query parameter via
 *     [validateWebSocketToken]; an absent, expired, or invalid token closes the
 *     connection with [WEBSOCKET_UNAUTHORIZED_CLOSE]. When [jwtConfig] /
 *     [jwkProvider] are `null` (auth disabled for smoke/dev) the check is
 *     skipped.
 *  2. **Scope-filters** the subscriber by the user's `X-User-Books` access —
 *     the JWT `books` claim read via [webSocketTokenBooks]. The subscriber is
 *     registered with that book scope so the [CopilotBroadcaster] only delivers
 *     pushes for books the user is entitled to. A user with no `books` claim
 *     has wildcard scope and receives every push.
 *
 * The channel is push-only: client frames are ignored beyond an informational
 * reply, exactly as `/ws/alerts` behaves.
 */
fun Route.copilotWebSocket(
    broadcaster: CopilotBroadcaster,
    jwtConfig: JwtConfig? = null,
    jwkProvider: JwkProvider? = null,
) {
    webSocket("/ws/copilot") {
        if (jwtConfig != null && jwkProvider != null && call.validateWebSocketToken(jwtConfig, jwkProvider) == null) {
            close(WEBSOCKET_UNAUTHORIZED_CLOSE)
            return@webSocket
        }
        val books = call.webSocketTokenBooks()
        broadcaster.addSubscriber(this, books)
        try {
            for (frame in incoming) {
                // Copilot WebSocket is push-only; ignore client messages.
                if (frame is Frame.Text) {
                    send(Frame.Text("""{"info":"This is a push-only channel"}"""))
                }
            }
        } finally {
            broadcaster.removeSession(this)
        }
    }
}
