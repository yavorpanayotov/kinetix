// NOTE: This package (com.kinetix.position.fix) is intended for future extraction
// into a standalone fix-gateway service per ADR-0035.

package com.kinetix.position.fix

import org.slf4j.LoggerFactory

/**
 * Emits an order cancel to the venue side. Per ADR-0035, the production implementation
 * lives in `fix-gateway` and translates the request into FIX `OrderCancelRequest`
 * (35=F). Until that service ships, the position-service uses [LoggingOrderCancelEmitter]
 * which records the intent but does not actually reach a venue. This is acceptable for
 * the launch of audit A-13 because:
 *
 *   - The state-side transition to [OrderStatus.EXPIRED] is the spec-mandated invariant.
 *   - Without an outbound FIX cancel the order may still be live at the venue, but
 *     `ScheduledOrderExpirySweeper` is conservative and the order's terminal status in
 *     the DB prevents new fills from being credited.
 *
 * When fix-gateway lands, swap [LoggingOrderCancelEmitter] for the gRPC-backed
 * implementation; the rest of the sweeper stays unchanged.
 */
interface OrderCancelEmitter {
    suspend fun emitCancel(order: Order, reason: CancelReason)
}

enum class CancelReason {
    DAY_ORDER_EXPIRY,
    GTD_EXPIRY,
    USER_INITIATED,
    RISK_LIMIT_BREACH,
}

/** Stub implementation used until fix-gateway is extracted (ADR-0035 phases 1-2). */
class LoggingOrderCancelEmitter : OrderCancelEmitter {
    private val logger = LoggerFactory.getLogger(LoggingOrderCancelEmitter::class.java)

    override suspend fun emitCancel(order: Order, reason: CancelReason) {
        logger.info(
            "OrderCancel emitted (stub — fix-gateway not yet wired): orderId={}, instrument={}, reason={}, tif={}",
            order.orderId, order.instrumentId, reason, order.timeInForce,
        )
    }
}
