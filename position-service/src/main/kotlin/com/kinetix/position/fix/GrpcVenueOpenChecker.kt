package com.kinetix.position.fix

import com.google.protobuf.Timestamp
import com.kinetix.common.execution.VenueOpenChecker
import com.kinetix.proto.execution.FixGatewayGrpc
import com.kinetix.proto.execution.IsVenueOpenRequest
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Production [VenueOpenChecker] (ADR-0035 phase 2). Calls fix-gateway's
 * `IsVenueOpen` RPC; on RPC failure falls back to "always open" so the sweeper
 * still progresses (matches today's best-effort failure mode) and emits the
 * `venue_cutoff_check_failed_total{venue}` signal via the failure callback.
 */
class GrpcVenueOpenChecker(
    channel: ManagedChannel,
    private val onRpcFailure: (venue: String, error: Throwable) -> Unit = { _, _ -> },
    private val rpcDeadlineMs: Long = 1_000L,
    private val fallbackOnFailure: Boolean = true,
) : VenueOpenChecker {

    private val logger = LoggerFactory.getLogger(GrpcVenueOpenChecker::class.java)
    private val stub: FixGatewayGrpc.FixGatewayBlockingStub =
        FixGatewayGrpc.newBlockingStub(channel)

    override fun isOpen(venue: String, at: Instant): Boolean {
        val request = IsVenueOpenRequest.newBuilder()
            .setVenue(venue)
            .setAt(Timestamp.newBuilder().setSeconds(at.epochSecond).setNanos(at.nano).build())
            .build()
        return try {
            stub.withDeadlineAfter(rpcDeadlineMs, TimeUnit.MILLISECONDS).isVenueOpen(request).open
        } catch (e: StatusRuntimeException) {
            logger.warn("IsVenueOpen RPC failed for venue={}: status={}", venue, e.status, e)
            onRpcFailure(venue, e)
            fallbackOnFailure
        }
    }
}
