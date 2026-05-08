// NOTE: This package (com.kinetix.position.fix) is intended for future extraction
// into a standalone fix-adapter service once the Gradle multi-module setup permits.
// See docs/plans/trader-review-team-plan-23.03.2026.md — Direction 5.

package com.kinetix.position.fix

enum class OrderStatus {
    PENDING_RISK_CHECK,
    APPROVED,
    REJECTED,
    SENT,
    PARTIAL,
    FILLED,
    CANCELLED,
    EXPIRED,

    /**
     * Routing to fix-gateway failed (FIX session was down or gRPC RPC exceeded its
     * deadline) per ADR-0035 phase 4. Non-terminal: the trader retries via the UI's
     * retry CTA, which reuses the original `clOrdId` so fix-gateway reconciles via
     * FIX 35=H `OrderStatusRequest` rather than producing a duplicate venue order.
     */
    PENDING_FAILED;

    val isTerminal: Boolean
        get() = this in setOf(FILLED, CANCELLED, EXPIRED, REJECTED)
}
