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
 *  - **7.5 (this checkbox)** introduces the *publish* side. The internal
 *    route `POST /internal/copilot/push` builds a [CopilotWebSocketMessage]
 *    from the posted payload and calls [broadcast] to enqueue it for fan-out.
 *  - **7.6** adds the `/ws/copilot` WebSocket route — JWT auth on connect and
 *    `X-User-Books` scope filtering — which registers subscriber sessions via
 *    [addSession] / [removeSession]. Until 7.6 wires that route there are no
 *    subscribers, so [broadcast] is a no-op fan-out, but the publish path is
 *    already exercised end-to-end by `CopilotInternalPushAcceptanceTest`.
 *
 * The class is `open` so tests can subclass it to observe enqueued messages
 * without standing up a real WebSocket client.
 */
open class CopilotBroadcaster {

    private val json = Json { encodeDefaults = true }
    private val sessions = ConcurrentHashMap.newKeySet<WebSocketServerSession>()

    fun addSession(session: WebSocketServerSession) {
        sessions.add(session)
    }

    fun removeSession(session: WebSocketServerSession) {
        sessions.remove(session)
    }

    /**
     * Enqueue [message] for fan-out to all subscribed `/ws/copilot` clients.
     * Dead sessions (closed connections) are pruned as a side effect.
     */
    open suspend fun broadcast(message: CopilotWebSocketMessage) {
        val text = json.encodeToString(message)
        val dead = mutableListOf<WebSocketServerSession>()
        for (session in sessions) {
            try {
                session.send(Frame.Text(text))
            } catch (_: Exception) {
                dead.add(session)
            }
        }
        for (session in dead) {
            removeSession(session)
        }
    }
}
