// NOTE: This package (com.kinetix.position.fix) is intended for future extraction
// into a standalone fix-gateway service per ADR-0035.

package com.kinetix.position.fix

import com.kinetix.common.execution.CancelReason
import com.kinetix.common.execution.OrderCancelEmitter
import com.kinetix.common.execution.VenueOpenChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * Recurring sweeper that transitions DAY and GTD orders to [OrderStatus.EXPIRED] when
 * they pass their venue cutoff (DAY) or specified expiry (GTD). Implements the
 * `ExpireDayOrder` rule from `execution.allium:461-474` (audit A-13).
 *
 * For each candidate the sweeper:
 * 1. Calls [OrderCancelEmitter.emitCancel] best-effort — the venue may already have
 *    filled or cancelled the order; venue-side resolution is authoritative and arrives
 *    asynchronously via execution reports.
 * 2. Updates the order's status to EXPIRED with a riskCheckResult tag indicating the
 *    expiry reason. The transition is idempotent: re-running over an already-EXPIRED
 *    order is a no-op because [findOpenDayAndGtdOrders] excludes terminal statuses.
 *
 * Single-instance leadership is the operator's responsibility today (HPA pin to 1
 * replica per the deploy chart). When position-service grows beyond a single instance
 * a Postgres advisory lock will be needed to avoid double-firing; that's a follow-on
 * tracked under ADR-0035.
 *
 * @param orderRepository    Source of candidate orders + status-transition target.
 * @param venueOpenChecker   Resolves whether the venue session is open (per ADR-0035
 *                           phase 2 the canonical source is fix-gateway via the
 *                           `IsVenueOpen` gRPC RPC; the in-process registry no
 *                           longer lives in position-service).
 * @param cancelEmitter      Emits the venue cancel (FIX 35=F via fix-gateway).
 * @param venueResolver      Maps an order to its venue. Defaults to NYSE for orders
 *                           without an explicit venue (matches existing routing).
 * @param intervalMillis     How often the sweeper runs (60s default).
 * @param clock              Injectable for tests.
 */
class ScheduledOrderExpirySweeper(
    private val orderRepository: ExecutionOrderRepository,
    private val venueOpenChecker: VenueOpenChecker,
    private val cancelEmitter: OrderCancelEmitter,
    private val venueResolver: (Order) -> String = { "NYSE" },
    private val intervalMillis: Long = 60_000L,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(ScheduledOrderExpirySweeper::class.java)

    /** Run the sweeper as a long-lived coroutine. */
    suspend fun start() {
        while (coroutineContext.isActive) {
            sweepOnce()
            delay(intervalMillis)
        }
    }

    /**
     * Single pass: find candidates, expire those past their cutoff. Returns the count
     * of orders expired so callers (tests, metrics) can assert behaviour.
     */
    suspend fun sweepOnce(): Int {
        val now = clock.instant()
        val candidates = orderRepository.findOpenDayAndGtdOrders()
        if (candidates.isEmpty()) return 0

        var expired = 0
        for (order in candidates) {
            val reason = expiryReasonFor(order, now) ?: continue
            try {
                cancelEmitter.emitCancel(
                    orderId = order.orderId,
                    venue = venueResolver(order),
                    venueOrderId = null,
                    reason = reason,
                )
            } catch (e: Exception) {
                logger.warn(
                    "OrderCancelEmitter failed for orderId={} reason={}: {} — proceeding with state transition",
                    order.orderId, reason, e.message,
                )
            }
            orderRepository.updateStatus(
                orderId = order.orderId,
                status = OrderStatus.EXPIRED,
                riskCheckResult = reason.name,
                riskCheckDetails = "Auto-expired by ScheduledOrderExpirySweeper at $now",
            )
            expired += 1
            logger.info(
                "Order auto-expired: orderId={} venue={} tif={} reason={}",
                order.orderId, venueResolver(order), order.timeInForce, reason,
            )
        }
        if (expired > 0) {
            logger.info(
                "Sweeper pass complete: {} of {} candidates expired",
                expired, candidates.size,
            )
        }
        return expired
    }

    private fun expiryReasonFor(order: Order, now: Instant): CancelReason? = when (order.timeInForce) {
        TimeInForce.DAY -> if (!venueOpenChecker.isOpen(venueResolver(order), now))
            CancelReason.DAY_ORDER_EXPIRY else null
        TimeInForce.GTD -> {
            val expiresAt = order.expiresAt
            if (expiresAt != null && !expiresAt.isAfter(now)) CancelReason.GTD_EXPIRY else null
        }
        else -> null
    }
}
