package com.kinetix.common.execution

/**
 * Outcome of a [FixGatewayClient.placeOrder] call. Mirrors the fields on the proto
 * `kinetix.execution.PlaceOrderResponse` while keeping callers free of gRPC types.
 *
 * - [venueOrderId] is populated only when [status] is [PlaceOrderStatus.PENDING_NEW]
 *   (the venue's FIX tag 37 OrderID).
 * - [rejectReason] is populated for [PlaceOrderStatus.REJECTED]; adapters may also
 *   surface a free-text detail on transport-level failures (SESSION_DOWN /
 *   DEADLINE_EXCEEDED) so logs preserve the cause.
 *
 * Created in `common/` per ADR-0035 phase 4 so position-service depends on the
 * abstraction rather than the gRPC stub.
 */
data class PlaceOrderResult(
    val clOrdId: String,
    val status: PlaceOrderStatus,
    val venueOrderId: String? = null,
    val rejectReason: String? = null,
)
