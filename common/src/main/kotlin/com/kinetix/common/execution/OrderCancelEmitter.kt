package com.kinetix.common.execution

/**
 * Emits an order cancel to the venue side. Per ADR-0035, the production implementation
 * lives in `fix-gateway` and translates the request into FIX `OrderCancelRequest`
 * (35=F). The interface is promoted to `common/` (phase 2 commit 1) so callers depend
 * on the abstraction rather than on a position-service or fix-gateway concrete type.
 *
 * Two concrete implementations exist:
 *   - `LoggingOrderCancelEmitter` (position-service, dev-mode fallback) — logs and returns.
 *   - `GrpcOrderCancelEmitter` (position-service, default) — calls fix-gateway via gRPC.
 *
 * The interface uses primitive parameters rather than a domain `Order` type so `common/`
 * does not need to depend on position-service's order model.
 */
interface OrderCancelEmitter {
    /**
     * Emit a cancel for [orderId] at [venue].
     *
     * @param orderId        Position-service-minted order identifier (UUID v4). Becomes
     *                       the FIX `OrigClOrdID` (tag 41) on the outbound 35=F.
     * @param venue          Target venue identifier (e.g. NYSE, NASDAQ, LSE, TSE, HKEX).
     * @param venueOrderId   FIX `OrderID` (tag 37) assigned by the venue at PENDING_NEW.
     *                       Required by most venues for 35=F. Null only if the order has
     *                       not yet reached PENDING_NEW — in that case the gRPC adapter
     *                       returns an error and the caller surfaces a defect.
     * @param reason         Why this cancel is being emitted; surfaced on metrics labels.
     * @param correlationId  Trace-propagation correlation id; null lets the adapter mint one.
     */
    suspend fun emitCancel(
        orderId: String,
        venue: String,
        venueOrderId: String?,
        reason: CancelReason,
        correlationId: String? = null,
    )
}
