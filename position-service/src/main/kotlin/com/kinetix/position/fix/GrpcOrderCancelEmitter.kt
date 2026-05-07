package com.kinetix.position.fix

import com.kinetix.common.execution.CancelReason
import com.kinetix.common.execution.OrderCancelEmitter
import com.kinetix.proto.execution.CancelOrderRequest
import com.kinetix.proto.execution.CancelOrderResponse
import com.kinetix.proto.execution.FixGatewayGrpc
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.TimeUnit
import com.kinetix.proto.execution.CancelReason as ProtoCancelReason

/**
 * Production [OrderCancelEmitter] (ADR-0035 phase 2). Translates the local
 * primitive-typed cancel into a `CancelOrderRequest` gRPC call against
 * fix-gateway. Recorded as a `cancel_attempts` row regardless of outcome so
 * the ghost-fill alerter (FIXExecutionReportProcessor) can correlate failed
 * cancels against late venue fills.
 *
 * `venueOrderId` is required by most venues for FIX 35=F. When null (the
 * order never reached PENDING_NEW because phase 4 hasn't shipped yet) the
 * adapter records the attempt as INVALID_REQUEST without making an RPC; the
 * sweeper's state-side EXPIRED transition still proceeds.
 */
class GrpcOrderCancelEmitter(
    channel: ManagedChannel,
    private val cancelAttemptRecorder: CancelAttemptRecorder,
    private val rpcDeadlineMs: Long = 5_000L,
    private val clock: () -> Instant = Instant::now,
) : OrderCancelEmitter {

    private val logger = LoggerFactory.getLogger(GrpcOrderCancelEmitter::class.java)
    private val stub: FixGatewayGrpc.FixGatewayBlockingStub =
        FixGatewayGrpc.newBlockingStub(channel)

    override suspend fun emitCancel(
        orderId: String,
        venue: String,
        venueOrderId: String?,
        reason: CancelReason,
        correlationId: String?,
    ) {
        if (venueOrderId.isNullOrBlank()) {
            // Phase-4 PlaceOrder hasn't populated this yet; record + bail without
            // an RPC so the sweeper's EXPIRED state transition continues unimpeded.
            cancelAttemptRecorder.record(
                orderId = orderId,
                venue = venue,
                status = CancelAttemptStatus.INVALID_REQUEST,
                attemptedAt = clock(),
                detail = "venueOrderId is null — order never reached PENDING_NEW",
            )
            logger.warn("Cancel skipped: orderId={} venue={} reason={} (venueOrderId null)", orderId, venue, reason)
            return
        }

        val request = CancelOrderRequest.newBuilder()
            .setClOrdId(orderId)
            .setVenue(venue)
            .setVenueOrderId(venueOrderId)
            .setReason(toProto(reason))
            .also { if (!correlationId.isNullOrBlank()) it.correlationId = correlationId }
            .build()

        val response: CancelOrderResponse = try {
            stub.withDeadlineAfter(rpcDeadlineMs, TimeUnit.MILLISECONDS).cancelOrder(request)
        } catch (e: StatusRuntimeException) {
            cancelAttemptRecorder.record(
                orderId = orderId,
                venue = venue,
                status = CancelAttemptStatus.RPC_FAILED,
                attemptedAt = clock(),
                detail = "${e.status.code}: ${e.status.description ?: ""}",
            )
            logger.warn("CancelOrder RPC failed: orderId={} venue={} status={}", orderId, venue, e.status, e)
            return
        }

        val mappedStatus = when (response.status) {
            CancelOrderResponse.Status.ACCEPTED -> CancelAttemptStatus.ACCEPTED
            CancelOrderResponse.Status.SESSION_DOWN -> CancelAttemptStatus.SESSION_DOWN
            CancelOrderResponse.Status.UNKNOWN_VENUE -> CancelAttemptStatus.UNKNOWN_VENUE
            CancelOrderResponse.Status.INVALID_REQUEST -> CancelAttemptStatus.INVALID_REQUEST
            else -> CancelAttemptStatus.RPC_FAILED
        }

        cancelAttemptRecorder.record(
            orderId = orderId,
            venue = venue,
            status = mappedStatus,
            attemptedAt = clock(),
            detail = response.detail,
        )

        if (mappedStatus != CancelAttemptStatus.ACCEPTED) {
            logger.warn(
                "CancelOrder non-accepted: orderId={} venue={} status={} detail={}",
                orderId, venue, mappedStatus, response.detail,
            )
        }
    }

    private fun toProto(reason: CancelReason): ProtoCancelReason = when (reason) {
        CancelReason.DAY_ORDER_EXPIRY -> ProtoCancelReason.DAY_ORDER_EXPIRY
        CancelReason.GTD_EXPIRY -> ProtoCancelReason.GTD_EXPIRY
        CancelReason.USER_INITIATED -> ProtoCancelReason.USER_INITIATED
        CancelReason.RISK_LIMIT_BREACH -> ProtoCancelReason.RISK_LIMIT_BREACH
    }
}
