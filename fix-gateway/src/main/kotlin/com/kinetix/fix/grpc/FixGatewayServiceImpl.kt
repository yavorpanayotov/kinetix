package com.kinetix.fix.grpc

import com.google.protobuf.Timestamp
import com.kinetix.fix.session.CancelMessageBuilder
import com.kinetix.fix.session.FixSessionSender
import com.kinetix.fix.session.SendOutcome
import com.kinetix.fix.venue.VenueCutoffRegistry
import com.kinetix.fix.venue.VenueSessionRegistry
import com.kinetix.proto.execution.CancelOrderRequest
import com.kinetix.proto.execution.CancelOrderResponse
import com.kinetix.proto.execution.FixGatewayGrpc
import com.kinetix.proto.execution.IsVenueOpenRequest
import com.kinetix.proto.execution.IsVenueOpenResponse
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant

/**
 * Server-side implementation of the [FixGateway] gRPC contract. ADR-0035 phase 2
 * delivers `CancelOrder` and `IsVenueOpen` only; `PlaceOrder` lands in phase 4.
 *
 * The implementation is intentionally thin — venue routing is delegated to the
 * registries, FIX message construction to [CancelMessageBuilder], wire delivery
 * to [FixSessionSender]. This keeps the gRPC layer testable without booting a
 * QuickFIX/J Initiator and lets the failure modes map cleanly onto the proto
 * `Status` enum.
 *
 * Side / OrderQty / Symbol on the cancel message come from a fix_message_log
 * lookup of the original 35=D in the production phase-4 path. Phase 2 has no
 * 35=D in the log (placement is still in-process in position-service), so the
 * cancel attempts will return SESSION_DOWN in real production usage — the path
 * is wired and observable, but exercised end-to-end only after phase 4.
 */
class FixGatewayServiceImpl(
    private val venueSessionRegistry: VenueSessionRegistry,
    private val venueCutoffRegistry: VenueCutoffRegistry,
    private val cancelMessageBuilder: CancelMessageBuilder,
    private val sessionSender: FixSessionSender,
    private val originalOrderLookup: OriginalOrderLookup,
    private val clock: () -> Instant = Instant::now,
) : FixGatewayGrpc.FixGatewayImplBase() {

    private val logger = LoggerFactory.getLogger(FixGatewayServiceImpl::class.java)

    /**
     * Resolves Side / OrderQty / Symbol for a previous 35=D so the 35=F cancel
     * can be built. Phase 2 supplies an in-memory implementation that returns
     * null for every clOrdID (no 35=D in the log yet); phase 4 wires the real
     * `fix_message_log` query.
     */
    fun interface OriginalOrderLookup {
        fun lookup(venue: String, clOrdId: String): OriginalOrder?
    }

    data class OriginalOrder(
        val symbol: String,
        val side: Char,
        val orderQty: BigDecimal,
    )

    // -----------------------------------------------------------------------
    // CancelOrder
    // -----------------------------------------------------------------------

    override fun cancelOrder(
        request: CancelOrderRequest,
        responseObserver: StreamObserver<CancelOrderResponse>,
    ) {
        val response = handleCancel(request)
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    }

    internal fun handleCancel(request: CancelOrderRequest): CancelOrderResponse {
        if (request.clOrdId.isBlank()) {
            return reject(request, "cl_ord_id must be non-empty")
        }
        val session = venueSessionRegistry.lookup(request.venue)
            ?: return unknownVenue(request)
        if (request.venueOrderId.isBlank()) {
            return reject(request, "venue_order_id is required (FIX tag 37)")
        }
        val original = originalOrderLookup.lookup(session.venue, request.clOrdId)
            ?: return reject(request, "original order not found in fix_message_log for clOrdID=${request.clOrdId}")

        val message = cancelMessageBuilder.build(
            session = session,
            inputs = CancelMessageBuilder.BuilderInputs(
                origClOrdId = request.clOrdId,
                venueOrderId = request.venueOrderId,
                symbol = original.symbol,
                side = original.side,
                orderQty = original.orderQty,
                transactTime = clock(),
            ),
        )

        return when (val outcome = sessionSender.send(session.venue, message)) {
            SendOutcome.Sent -> CancelOrderResponse.newBuilder()
                .setClOrdId(request.clOrdId)
                .setStatus(CancelOrderResponse.Status.ACCEPTED)
                .build()
            SendOutcome.SessionDown -> CancelOrderResponse.newBuilder()
                .setClOrdId(request.clOrdId)
                .setStatus(CancelOrderResponse.Status.SESSION_DOWN)
                .setDetail("FIX session for ${session.venue} is not connected")
                .build()
            SendOutcome.UnknownVenue -> unknownVenue(request)
        }.also {
            logger.info(
                "CancelOrder venue={} clOrdId={} venueOrderId={} reason={} status={}",
                session.venue, request.clOrdId, request.venueOrderId, request.reason, it.status,
            )
        }
    }

    private fun reject(request: CancelOrderRequest, detail: String): CancelOrderResponse =
        CancelOrderResponse.newBuilder()
            .setClOrdId(request.clOrdId)
            .setStatus(CancelOrderResponse.Status.INVALID_REQUEST)
            .setDetail(detail)
            .build()

    private fun unknownVenue(request: CancelOrderRequest): CancelOrderResponse =
        CancelOrderResponse.newBuilder()
            .setClOrdId(request.clOrdId)
            .setStatus(CancelOrderResponse.Status.UNKNOWN_VENUE)
            .setDetail("venue '${request.venue}' is not registered")
            .build()

    // -----------------------------------------------------------------------
    // IsVenueOpen
    // -----------------------------------------------------------------------

    override fun isVenueOpen(
        request: IsVenueOpenRequest,
        responseObserver: StreamObserver<IsVenueOpenResponse>,
    ) {
        val response = handleIsVenueOpen(request)
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    }

    internal fun handleIsVenueOpen(request: IsVenueOpenRequest): IsVenueOpenResponse {
        val at: Instant = if (request.hasAt()) {
            Instant.ofEpochSecond(request.at.seconds, request.at.nanos.toLong())
        } else {
            clock()
        }
        if (!venueCutoffRegistry.knows(request.venue)) {
            return IsVenueOpenResponse.newBuilder().setOpen(false).build()
        }
        val open = venueCutoffRegistry.isOpen(request.venue, at)
        val builder = IsVenueOpenResponse.newBuilder().setOpen(open)
        if (open) {
            val nextClose = venueCutoffRegistry.nextClose(request.venue, at)
            builder.nextClose = Timestamp.newBuilder()
                .setSeconds(nextClose.epochSecond)
                .setNanos(nextClose.nano)
                .build()
        }
        return builder.build()
    }
}
