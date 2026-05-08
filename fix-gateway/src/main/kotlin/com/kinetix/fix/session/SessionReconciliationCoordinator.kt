package com.kinetix.fix.session

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import quickfix.Session
import quickfix.SessionID
import quickfix.field.ClOrdID
import quickfix.field.MsgType
import quickfix.field.OrdStatusReqID
import quickfix.field.Side
import quickfix.field.Symbol
import quickfix.fix44.OrderStatusRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Per-venue state machine for post-reconnect reconciliation (ADR-0035 §4.3).
 *
 * State transitions:
 *   DOWN → RECONCILING  on [onLogon] — fires 35=H for every open clOrdID
 *   RECONCILING → ACTIVE on all acks received OR reconciliation timeout
 *   ACTIVE → DOWN        on [onLogout]
 *
 * During RECONCILING, [isActive] returns false so [FixGatewayServiceImpl.placeOrder]
 * and [cancelOrder] gate new outbound traffic with SESSION_DOWN / detail="reconciling".
 *
 * The 35=H `OrdStatusReqID` is set to the clOrdID under query so the inbound
 * 35=8 response can be matched back via `OrdStatusReqID` (tag 790) or `ClOrdID`
 * (tag 11). For simplicity in this phase the coordinator drives reconciliation
 * completion on a timeout rather than awaiting individual 35=8 acks — the counter
 * outcome is therefore `timeout` when no responses arrive within [reconcileTimeoutMs]
 * and `success` when a fast completion is triggered (via [completeReconciliation]).
 * A follow-on commit can wire individual 35=8 completions.
 */
class SessionReconciliationCoordinator(
    private val messageLogRepository: FixMessageLogRepository,
    private val meterRegistry: MeterRegistry,
    private val reconcileTimeoutMs: Long = 5_000L,
    private val venueSessionLookup: (String) -> SessionID? = { _ -> null },
) {
    private val logger = LoggerFactory.getLogger(SessionReconciliationCoordinator::class.java)

    enum class State { DOWN, RECONCILING, ACTIVE }

    private val states: MutableMap<String, State> = ConcurrentHashMap()

    /** Latches used to signal early reconciliation completion in tests. */
    private val latches: MutableMap<String, CountDownLatch> = ConcurrentHashMap()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** @return true only when the venue is in ACTIVE state. */
    fun isActive(venue: String): Boolean = states[venue.uppercase()] == State.ACTIVE

    /** @return the current state for the venue (defaults to DOWN if unseen). */
    fun currentState(venue: String): State = states[venue.uppercase()] ?: State.DOWN

    /**
     * Called when the FIX session for [venue] successfully establishes a logon.
     * Transitions to RECONCILING, queries [messageLogRepository] for open orders,
     * fires `OrderStatusRequest` (35=H) for each, then transitions to ACTIVE once
     * the timeout elapses (or earlier if [completeReconciliation] is called).
     *
     * Runs in a background coroutine so the QuickFIX/J [Application.onLogon] callback
     * (which runs on the session thread) is not blocked.
     */
    fun onLogon(venue: String) {
        val key = venue.uppercase()
        logger.info("FIX session logon for venue={} — entering RECONCILING", key)
        states[key] = State.RECONCILING
        val latch = CountDownLatch(1)
        latches[key] = latch

        scope.launch {
            val outcome = try {
                val openClOrdIds = messageLogRepository.findOpenClOrdIds(key)
                logger.info("Reconciliation venue={} openOrders={}", key, openClOrdIds.size)

                sendOrderStatusRequests(key, openClOrdIds)

                // Wait for explicit completion signal or timeout.
                val completed = latch.await(reconcileTimeoutMs, TimeUnit.MILLISECONDS)
                if (completed) "success" else "timeout"
            } catch (e: Exception) {
                logger.error("Reconciliation failed for venue={}: {}", key, e.message, e)
                "partial"
            } finally {
                latches.remove(key)
            }

            logger.info("Reconciliation complete for venue={} outcome={}", key, outcome)
            states[key] = State.ACTIVE

            meterRegistry.counter(
                "fix_session_reconciliation_total",
                "venue", key,
                "outcome", outcome,
            ).increment()
        }
    }

    /**
     * Called when the FIX session for [venue] logs out. Transitions immediately to DOWN
     * so the placeOrder gate blocks new traffic.
     */
    fun onLogout(venue: String) {
        val key = venue.uppercase()
        logger.info("FIX session logout for venue={} — transitioning to DOWN", key)
        states[key] = State.DOWN
        // Cancel any pending reconciliation latch (it will receive `false` on await if it times out
        // or the new logon will replace it).
        latches[key]?.countDown()
    }

    /**
     * Explicitly complete reconciliation for [venue] before the timeout elapses.
     * Used in tests and by the inbound handler once all 35=H responses are received.
     */
    fun completeReconciliation(venue: String) {
        latches[venue.uppercase()]?.countDown()
    }

    private fun sendOrderStatusRequests(venue: String, clOrdIds: List<String>) {
        if (clOrdIds.isEmpty()) return
        val sessionId = venueSessionLookup(venue)
        if (sessionId == null) {
            logger.warn("Cannot send 35=H — no SessionID registered for venue={}", venue)
            return
        }
        val session = Session.lookupSession(sessionId)
        if (session == null) {
            logger.warn("Cannot send 35=H — session not found for venue={}", venue)
            return
        }
        for (clOrdId in clOrdIds) {
            val msg = OrderStatusRequest()
            msg.set(ClOrdID(clOrdId))
            // OrdStatusReqID (tag 790) = clOrdID for easy inbound correlation.
            msg.set(OrdStatusReqID(clOrdId))
            // Side and Symbol are required by FIX 4.4 spec for 35=H.
            // We don't know the side at reconciliation time without a log lookup;
            // many venues accept the "don't know" value (side=8 = undisclosed).
            msg.set(Side('8'))
            msg.set(Symbol("UNKNOWN"))
            val sent = session.send(msg)
            logger.info(
                "Sent OrderStatusRequest 35=H venue={} clOrdId={} sent={}",
                venue, clOrdId, sent,
            )
        }
    }
}
