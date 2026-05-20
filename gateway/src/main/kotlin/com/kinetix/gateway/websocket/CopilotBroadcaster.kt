package com.kinetix.gateway.websocket

import com.kinetix.gateway.dtos.CopilotPushRequest
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Envelope for an intraday-push frame delivered over `/ws/copilot`.
 *
 * Wraps the [CopilotPushRequest] payload posted by `ai-insights-service` to
 * the gateway's internal route. `type` is a constant discriminator so the UI
 * can dispatch frames by kind on a multiplexed channel.
 */
@Serializable
data class CopilotWebSocketMessage(
    val type: String = "intraday_push",
    val push: CopilotPushRequest,
)

/**
 * Broadcasts intraday Copilot push frames to subscribed `/ws/copilot`
 * WebSocket clients. Mirrors [AlertBroadcaster] (ADR-0016 / ADR-0036).
 *
 * Split across checkboxes 7.5 and 7.6 of `plans/ai-v2.md`:
 *
 *  - **7.5** introduced the *publish* side. The internal route
 *    `POST /internal/copilot/push` builds a [CopilotWebSocketMessage] from the
 *    posted payload and calls [broadcast] to fan it out.
 *  - **7.6 (this checkbox)** adds the `/ws/copilot` WebSocket route — JWT auth
 *    on connect and `X-User-Books` scope filtering — which registers
 *    subscriber sessions via [addSubscriber] / [removeSession].
 *
 * Fan-out is **scope-filtered**: a [CopilotWebSocketMessage] is delivered only
 * to subscribers whose book scope includes the push's `book_id`. A subscriber
 * registered with `books = null` has wildcard scope (risk managers,
 * compliance, admins) and receives every push. See [CopilotSubscriber].
 *
 * The class is `open` so tests can subclass it to observe enqueued messages
 * without standing up a real WebSocket client.
 */
open class CopilotBroadcaster {

    private val json = Json { encodeDefaults = true }
    private val subscribers = ConcurrentHashMap.newKeySet<CopilotSubscriber>()

    /**
     * Register a `/ws/copilot` [session] scoped to [books]. A `null` [books]
     * grants wildcard scope — the subscriber then receives every push.
     */
    fun addSubscriber(session: WebSocketServerSession, books: Set<String>?) {
        subscribers.add(CopilotSubscriber(session, books))
    }

    /**
     * Register a `/ws/copilot` [session] with wildcard scope.
     *
     * Retained for callers that do not carry a book scope; equivalent to
     * `addSubscriber(session, books = null)`.
     */
    fun addSession(session: WebSocketServerSession) {
        addSubscriber(session, books = null)
    }

    /** Deregister every subscriber backed by [session]. */
    fun removeSession(session: WebSocketServerSession) {
        subscribers.removeIf { it.session == session }
    }

    /** Number of currently registered subscribers. Exposed for tests. */
    fun subscriberCount(): Int = subscribers.size

    /**
     * Fan [message] out to every subscribed `/ws/copilot` client whose book
     * scope includes the push's `book_id`. Wildcard-scoped subscribers receive
     * it unconditionally. Dead sessions (closed connections) are pruned as a
     * side effect.
     */
    open suspend fun broadcast(message: CopilotWebSocketMessage) {
        val text = json.encodeToString(message)
        val dead = mutableListOf<WebSocketServerSession>()
        for (subscriber in subscribers) {
            if (!subscriber.isScopedTo(message.push.bookId)) continue
            try {
                subscriber.session.send(Frame.Text(text))
            } catch (_: Exception) {
                dead.add(subscriber.session)
            }
        }
        for (session in dead) {
            removeSession(session)
        }
    }
}
