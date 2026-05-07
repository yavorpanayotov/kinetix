package com.kinetix.position.fix

import java.math.BigDecimal
import java.time.Instant

/**
 * A FIX 35=8 fill that arrived against an already-terminal order. Persisted
 * via [GhostFillRepository] and surfaced on the order detail panel; the
 * accompanying [com.kinetix.common.kafka.events.RiskBreakEvent] flows on
 * `risk.breaks`. Position is NOT auto-updated for ghost fills.
 *
 * @property priorStatus  The order's status at the moment the fill arrived
 *                        — must be one of EXPIRED / CANCELLED / REJECTED.
 */
data class GhostFill(
    val orderId: String,
    val priorStatus: OrderStatus,
    val venue: String,
    val fixExecId: String,
    val fillQty: BigDecimal,
    val fillPrice: BigDecimal,
    val cumulativeQty: BigDecimal,
    val detectedAt: Instant,
    val rawEvent: String,
) {
    init {
        require(priorStatus in setOf(OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.REJECTED)) {
            "priorStatus must be a terminal status that does not own the fill (got $priorStatus)"
        }
    }
}
