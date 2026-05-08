package com.kinetix.common.execution

/**
 * Outcome categories for a [FixGatewayClient.placeOrder] call. Mirrors the proto
 * `kinetix.execution.PlaceOrderResponse.Status` plus a [DEADLINE_EXCEEDED] value
 * for transport-level RPC timeouts (gRPC adapters map a `StatusRuntimeException`
 * with status `DEADLINE_EXCEEDED` onto this enum so callers don't need to know
 * about gRPC types).
 *
 * `OrderSubmissionService` treats [SESSION_DOWN] and [DEADLINE_EXCEEDED]
 * identically (both transition the order to `PENDING_FAILED`); the values are
 * kept distinct so metrics and logs preserve the cause. See ADR-0035 §4.5.
 */
enum class PlaceOrderStatus {
    /** Venue acknowledged the order with FIX 35=8 OrdStatus=A. `venueOrderId` is set. */
    PENDING_NEW,

    /** Venue rejected (35=8 OrdStatus=8 or 35=j business-reject). `rejectReason` is set. */
    REJECTED,

    /**
     * FIX session for the venue was down or in reconciliation mode. Caller may retry
     * with the SAME `clOrdId` once fix-gateway is healthy — fix-gateway reconciles via
     * FIX 35=H OrderStatusRequest rather than producing a duplicate venue order.
     */
    SESSION_DOWN,

    /** Venue is not registered in fix-gateway's `VenueSessionRegistry`. */
    UNKNOWN_VENUE,

    /** Request failed validation server-side (e.g. zero quantity, malformed price). */
    INVALID_REQUEST,

    /**
     * A previous PlaceOrder for the same `clOrdId` is still in-flight. Caller MUST NOT
     * retry with the same `clOrdId` until the original resolves; either wait or surface
     * "previous submission still in flight" to the trader.
     */
    DUPLICATE_IN_FLIGHT,

    /**
     * gRPC RPC exceeded its deadline. Treated identically to [SESSION_DOWN] by
     * `OrderSubmissionService` (transition to `PENDING_FAILED`).
     */
    DEADLINE_EXCEEDED,
}
