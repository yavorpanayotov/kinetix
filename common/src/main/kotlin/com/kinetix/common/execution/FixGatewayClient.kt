package com.kinetix.common.execution

import com.kinetix.common.model.Side
import java.math.BigDecimal
import java.time.Instant

/**
 * Submits a NewOrderSingle (FIX 35=D) to fix-gateway and synchronously waits
 * for the venue's first ExecutionReport (35=8 OrdStatus=A, "Pending New") up to
 * the per-venue timeout configured in `VenueSessionRegistry`. Per ADR-0035
 * phase 4, the production implementation lives in position-service
 * (`GrpcFixGatewayClient`) and translates this call into the
 * `kinetix.execution.FixGateway/PlaceOrder` gRPC RPC against fix-gateway:9105.
 *
 * The interface is promoted to `common/` so position-service depends on the
 * abstraction rather than on generated gRPC types. Acceptance tests stub it
 * directly.
 *
 * ## Idempotency
 *
 * Callers MUST reuse the original [clOrdId] when retrying after
 * [PlaceOrderStatus.SESSION_DOWN] or [PlaceOrderStatus.DEADLINE_EXCEEDED] —
 * fix-gateway reconciles via FIX 35=H `OrderStatusRequest` rather than
 * producing a duplicate venue order. A second call with the same [clOrdId]
 * while the first is still in-flight returns
 * [PlaceOrderStatus.DUPLICATE_IN_FLIGHT].
 *
 * ## Decimal carriage
 *
 * [quantity] and [limitPrice] are passed verbatim and serialised as proto
 * strings to avoid float drift. Negative or zero quantity results in
 * [PlaceOrderStatus.INVALID_REQUEST] server-side.
 */
interface FixGatewayClient {
    /**
     * Submit a new order to [venue].
     *
     * @param clOrdId           FIX tag 11 ClOrdID (UUID v4 minted by position-service).
     *                          Reused as Kafka partition key for `execution.reports`.
     * @param venue             Target venue identifier (e.g. NYSE, NASDAQ, LSE).
     * @param instrumentId      Reference-data instrument id; fix-gateway resolves
     *                          to FIX tag 55 Symbol.
     * @param side              BUY / SELL (FIX tag 54).
     * @param orderType         "MARKET" or "LIMIT" (phase 4 launch set; FIX tag 40).
     *                          Unknown values are rejected as INVALID_REQUEST.
     * @param quantity          Strictly positive decimal (FIX tag 38).
     * @param limitPrice        Required for LIMIT, must be null for MARKET. Negative
     *                          values are valid for synthetic instruments and are
     *                          preserved verbatim.
     * @param timeInForce       "DAY" / "GTC" / "IOC" / "FOK" / "GTD" (FIX tag 59).
     * @param expiresAt         Required when [timeInForce] is "GTD"; otherwise null.
     * @param correlationId     Trace-propagation correlation id; null lets the adapter
     *                          mint one.
     * @param venueAckTimeoutMs Optional per-call override of the per-venue default
     *                          in fix-gateway's `VenueSessionRegistry`. 0 = use venue
     *                          default (NYSE/NASDAQ co-lo 200ms; LSE 500ms;
     *                          TSE/HKEX 1000ms; EM brokers 5000ms).
     */
    suspend fun placeOrder(
        clOrdId: String,
        venue: String,
        instrumentId: String,
        side: Side,
        orderType: String,
        quantity: BigDecimal,
        limitPrice: BigDecimal?,
        timeInForce: String,
        expiresAt: Instant? = null,
        correlationId: String? = null,
        venueAckTimeoutMs: Int = 0,
    ): PlaceOrderResult
}
