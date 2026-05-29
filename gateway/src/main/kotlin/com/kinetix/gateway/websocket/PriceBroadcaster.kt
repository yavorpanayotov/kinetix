package com.kinetix.gateway.websocket

import com.kinetix.common.model.PricePoint
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class PriceBroadcaster {

    private val log = LoggerFactory.getLogger(PriceBroadcaster::class.java)

    private val json = Json { encodeDefaults = true }
    private val subscriptions = ConcurrentHashMap<String, MutableSet<WebSocketServerSession>>()

    fun subscribe(session: WebSocketServerSession, instrumentIds: List<String>) {
        for (id in instrumentIds) {
            subscriptions.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }.add(session)
        }
    }

    fun unsubscribe(session: WebSocketServerSession, instrumentIds: List<String>) {
        for (id in instrumentIds) {
            subscriptions[id]?.remove(session)
        }
    }

    fun removeSession(session: WebSocketServerSession) {
        for (sessions in subscriptions.values) {
            sessions.remove(session)
        }
    }

    suspend fun broadcast(point: PricePoint) {
        val sessions = subscriptions[point.instrumentId.value] ?: return
        val message = json.encodeToString(PriceUpdate.from(point))
        val dead = mutableListOf<WebSocketServerSession>()
        for (session in sessions) {
            try {
                session.send(Frame.Text(message))
            } catch (e: Exception) {
                log.debug("Price WebSocket session send failed — pruning dead session", e)
                dead.add(session)
            }
        }
        for (session in dead) {
            removeSession(session)
        }
    }
}
