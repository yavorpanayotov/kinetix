package com.kinetix.common.execution

/**
 * Why a cancel is being emitted. Carried over the gRPC contract to fix-gateway
 * (proto enum `kinetix.execution.CancelReason`) and surfaced on metrics labels.
 *
 * Promoted to `common/` per ADR-0035 phase 2 so position-service depends on the
 * abstraction rather than a fix-gateway implementation type.
 */
enum class CancelReason {
    DAY_ORDER_EXPIRY,
    GTD_EXPIRY,
    USER_INITIATED,
    RISK_LIMIT_BREACH,
}
