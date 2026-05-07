package com.kinetix.position.fix

import com.kinetix.common.execution.CancelReason
import com.kinetix.common.execution.OrderCancelEmitter
import org.slf4j.LoggerFactory

/**
 * Local-dev fallback for [OrderCancelEmitter]. Records the intent but does not reach
 * a venue. Selected when `FIX_GATEWAY_ENABLED=false`; production deploys use
 * [com.kinetix.position.fix.GrpcOrderCancelEmitter] (ADR-0035 phase 2).
 */
class LoggingOrderCancelEmitter : OrderCancelEmitter {
    private val logger = LoggerFactory.getLogger(LoggingOrderCancelEmitter::class.java)

    override suspend fun emitCancel(
        orderId: String,
        venue: String,
        venueOrderId: String?,
        reason: CancelReason,
        correlationId: String?,
    ) {
        logger.info(
            "OrderCancel emitted (logging stub): orderId={}, venue={}, venueOrderId={}, reason={}, correlationId={}",
            orderId, venue, venueOrderId, reason, correlationId,
        )
    }
}
