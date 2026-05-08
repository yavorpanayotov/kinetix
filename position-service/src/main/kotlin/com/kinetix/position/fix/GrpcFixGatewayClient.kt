package com.kinetix.position.fix

import com.kinetix.common.execution.FixGatewayClient
import com.kinetix.common.execution.PlaceOrderResult
import com.kinetix.common.execution.PlaceOrderStatus
import com.kinetix.common.model.Side
import com.kinetix.proto.execution.FixGatewayGrpc
import com.kinetix.proto.execution.PlaceOrderRequest
import com.kinetix.proto.execution.PlaceOrderResponse
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit
import com.kinetix.proto.execution.OrderType as ProtoOrderType
import com.kinetix.proto.execution.Side as ProtoSide
import com.kinetix.proto.execution.TimeInForce as ProtoTimeInForce

/**
 * Production [FixGatewayClient] (ADR-0035 phase 4). Translates a primitive-typed
 * [placeOrder] call into a `PlaceOrderRequest` gRPC call against fix-gateway.
 *
 * gRPC deadline is set to `venue_ack_timeout_ms + grace`, where the grace covers
 * correlator overhead. When the per-call timeout is 0 the adapter falls back to
 * [defaultDeadlineMs], which is sized for the worst-case venue band (5s for
 * EM brokers per `VenueSessionRegistry`).
 *
 * Transport-level failures translate to [PlaceOrderStatus] values rather than
 * exceptions so the caller (`OrderSubmissionService`) gets a uniform result type
 * to map to terminal `OrderStatus`. Specifically:
 *   - `DEADLINE_EXCEEDED` → [PlaceOrderStatus.DEADLINE_EXCEEDED]
 *   - `UNAVAILABLE` / `INTERNAL` → [PlaceOrderStatus.SESSION_DOWN]
 *   - everything else → [PlaceOrderStatus.SESSION_DOWN] with `rejectReason` set
 *     to the gRPC code so logs preserve the cause.
 */
class GrpcFixGatewayClient(
    channel: ManagedChannel,
    private val defaultDeadlineMs: Long = 5_500L,
    private val deadlineGraceMs: Long = 500L,
) : FixGatewayClient {

    private val logger = LoggerFactory.getLogger(GrpcFixGatewayClient::class.java)
    private val stub: FixGatewayGrpc.FixGatewayBlockingStub =
        FixGatewayGrpc.newBlockingStub(channel)

    override suspend fun placeOrder(
        clOrdId: String,
        venue: String,
        instrumentId: String,
        side: Side,
        orderType: String,
        quantity: BigDecimal,
        limitPrice: BigDecimal?,
        timeInForce: String,
        expiresAt: Instant?,
        correlationId: String?,
        venueAckTimeoutMs: Int,
    ): PlaceOrderResult {
        val request = PlaceOrderRequest.newBuilder()
            .setClOrdId(clOrdId)
            .setVenue(venue)
            .setInstrumentId(instrumentId)
            .setSide(toProtoSide(side))
            .setOrderType(toProtoOrderType(orderType))
            .setQuantity(quantity.toPlainString())
            .also { if (limitPrice != null) it.limitPrice = limitPrice.toPlainString() }
            .setTimeInForce(toProtoTimeInForce(timeInForce))
            .also { if (expiresAt != null) it.expiresAtIso = expiresAt.toString() }
            .also { if (!correlationId.isNullOrBlank()) it.correlationId = correlationId }
            .setVenueAckTimeoutMs(venueAckTimeoutMs)
            .build()

        val deadlineMs = if (venueAckTimeoutMs > 0) venueAckTimeoutMs + deadlineGraceMs else defaultDeadlineMs

        val response: PlaceOrderResponse = try {
            stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).placeOrder(request)
        } catch (e: StatusRuntimeException) {
            return mapTransportFailure(clOrdId, e)
        }

        return PlaceOrderResult(
            clOrdId = clOrdId,
            status = mapProtoStatus(response.status),
            venueOrderId = response.venueOrderId.takeIf { it.isNotBlank() },
            rejectReason = response.rejectReason.takeIf { it.isNotBlank() },
        )
    }

    private fun mapTransportFailure(clOrdId: String, e: StatusRuntimeException): PlaceOrderResult {
        val code = e.status.code
        val mapped = when (code) {
            Status.Code.DEADLINE_EXCEEDED -> PlaceOrderStatus.DEADLINE_EXCEEDED
            else -> PlaceOrderStatus.SESSION_DOWN
        }
        logger.warn(
            "PlaceOrder RPC transport failure: clOrdId={} grpcStatus={} description={}",
            clOrdId, code, e.status.description ?: "",
        )
        return PlaceOrderResult(
            clOrdId = clOrdId,
            status = mapped,
            venueOrderId = null,
            rejectReason = "grpc:${code}",
        )
    }

    private fun mapProtoStatus(proto: PlaceOrderResponse.Status): PlaceOrderStatus = when (proto) {
        PlaceOrderResponse.Status.PENDING_NEW -> PlaceOrderStatus.PENDING_NEW
        PlaceOrderResponse.Status.REJECTED -> PlaceOrderStatus.REJECTED
        PlaceOrderResponse.Status.SESSION_DOWN -> PlaceOrderStatus.SESSION_DOWN
        PlaceOrderResponse.Status.UNKNOWN_VENUE -> PlaceOrderStatus.UNKNOWN_VENUE
        PlaceOrderResponse.Status.INVALID_REQUEST -> PlaceOrderStatus.INVALID_REQUEST
        PlaceOrderResponse.Status.DUPLICATE_IN_FLIGHT -> PlaceOrderStatus.DUPLICATE_IN_FLIGHT
        PlaceOrderResponse.Status.STATUS_UNSPECIFIED, PlaceOrderResponse.Status.UNRECOGNIZED ->
            PlaceOrderStatus.SESSION_DOWN
    }

    private fun toProtoSide(side: Side): ProtoSide = when (side) {
        Side.BUY -> ProtoSide.BUY
        Side.SELL -> ProtoSide.SELL
    }

    private fun toProtoOrderType(orderType: String): ProtoOrderType = when (orderType.uppercase()) {
        "MARKET" -> ProtoOrderType.MARKET
        "LIMIT" -> ProtoOrderType.LIMIT
        else -> ProtoOrderType.ORDER_TYPE_UNSPECIFIED
    }

    private fun toProtoTimeInForce(tif: String): ProtoTimeInForce = when (tif.uppercase()) {
        "DAY" -> ProtoTimeInForce.TIF_DAY
        "GTC" -> ProtoTimeInForce.TIF_GTC
        "IOC" -> ProtoTimeInForce.TIF_IOC
        "FOK" -> ProtoTimeInForce.TIF_FOK
        "GTD" -> ProtoTimeInForce.TIF_GTD
        else -> ProtoTimeInForce.TIME_IN_FORCE_UNSPECIFIED
    }
}
