package com.kinetix.fix.grpc

import com.google.protobuf.Timestamp
import com.kinetix.fix.session.CancelMessageBuilder
import com.kinetix.fix.session.FixSessionSender
import com.kinetix.fix.session.NewOrderSingleBuilder
import com.kinetix.fix.session.PendingNewCorrelator
import com.kinetix.fix.session.SendOutcome
import com.kinetix.fix.session.SessionReconciliationCoordinator
import com.kinetix.fix.venue.VenueCutoffRegistry
import com.kinetix.fix.venue.VenueSession
import com.kinetix.fix.venue.VenueSessionRegistry
import com.kinetix.proto.execution.CancelOrderRequest
import com.kinetix.proto.execution.CancelOrderResponse
import com.kinetix.proto.execution.FixGatewayGrpc
import com.kinetix.proto.execution.IsVenueOpenRequest
import com.kinetix.proto.execution.IsVenueOpenResponse
import com.kinetix.proto.execution.PlaceOrderRequest
import com.kinetix.proto.execution.PlaceOrderResponse
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
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
    private val newOrderSingleBuilder: NewOrderSingleBuilder,
    private val pendingNewCorrelator: PendingNewCorrelator,
    private val sessionSender: FixSessionSender,
    private val originalOrderLookup: OriginalOrderLookup,
    private val clock: () -> Instant = Instant::now,
    private val reconciliationCoordinator: SessionReconciliationCoordinator? = null,
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

        // Gate: block cancels during reconciliation — same policy as placeOrder.
        if (reconciliationCoordinator != null && !reconciliationCoordinator.isActive(session.venue)) {
            return CancelOrderResponse.newBuilder()
                .setClOrdId(request.clOrdId)
                .setStatus(CancelOrderResponse.Status.SESSION_DOWN)
                .setDetail("reconciling")
                .build()
        }

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

    // -----------------------------------------------------------------------
    // PlaceOrder (ADR-0035 phase 4)
    // -----------------------------------------------------------------------

    override fun placeOrder(
        request: PlaceOrderRequest,
        responseObserver: StreamObserver<PlaceOrderResponse>,
    ) {
        val response = handlePlaceOrder(request)
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    }

    /**
     * Synchronous PlaceOrder pipeline:
     *
     *   1. Reject blank/invalid input (INVALID_REQUEST).
     *   2. Resolve venue (UNKNOWN_VENUE if not registered) and pick the per-venue
     *      ack timeout, optionally overridden by `venue_ack_timeout_ms`.
     *   3. Build the 35=D NewOrderSingle. Builder validation failures map to
     *      INVALID_REQUEST so the trader sees the reason rather than a 5xx.
     *   4. Register the correlator deferred BEFORE the on-wire send so an instant
     *      35=8 still finds an entry. The send callback throws a typed exception
     *      on SessionDown / UnknownVenue so the correlator unwinds the slot —
     *      otherwise the permit would leak on every failed send.
     *   5. Block on the deferred up to the venue-ack timeout; map outcomes onto
     *      the proto Status enum.
     *
     * The correlator's per-venue back-pressure cap surfaces as SESSION_DOWN with
     * detail `back_pressure` so the caller's UI bands it together with other
     * "venue routing unavailable" cases (per the UX contract). The 24h
     * fix_message_log idempotency lookup + 35=H reconciliation lands once the
     * Postgres-backed store wires in (out of scope for this commit; the
     * in-memory correlator already covers the in-flight duplicate case).
     */
    internal fun handlePlaceOrder(request: PlaceOrderRequest): PlaceOrderResponse {
        if (request.clOrdId.isBlank()) {
            return placeRejected(request, PlaceOrderResponse.Status.INVALID_REQUEST, "cl_ord_id must be non-empty")
        }
        if (request.instrumentId.isBlank()) {
            return placeRejected(request, PlaceOrderResponse.Status.INVALID_REQUEST, "instrument_id must be non-empty")
        }

        val session = venueSessionRegistry.lookup(request.venue)
            ?: return placeRejected(
                request,
                PlaceOrderResponse.Status.UNKNOWN_VENUE,
                "venue '${request.venue}' is not registered",
            )

        // Gate: reject new outbound orders while the session is reconciling post-reconnect.
        // The coordinator is optional (null in dev mode when sessions are disabled).
        if (reconciliationCoordinator != null && !reconciliationCoordinator.isActive(session.venue)) {
            val state = reconciliationCoordinator.currentState(session.venue)
            return placeRejected(
                request,
                PlaceOrderResponse.Status.SESSION_DOWN,
                "reconciling",
            ).also {
                logger.info(
                    "PlaceOrder blocked: venue={} clOrdId={} reason=reconciliation state={}",
                    session.venue, request.clOrdId, state,
                )
            }
        }

        val message = try {
            newOrderSingleBuilder.build(session, request, clock())
        } catch (e: IllegalArgumentException) {
            return placeRejected(request, PlaceOrderResponse.Status.INVALID_REQUEST, e.message ?: "invalid request")
        }

        val timeout = resolveTimeout(session, request)

        val response = try {
            pendingNewCorrelator.register(session.venue, request.clOrdId) {
                when (sessionSender.send(session.venue, message)) {
                    SendOutcome.Sent -> Unit
                    SendOutcome.SessionDown -> throw PlaceOrderSessionDown
                    SendOutcome.UnknownVenue -> throw PlaceOrderUnknownVenue
                }
            }
        } catch (_: PlaceOrderSessionDown) {
            return placeRejected(
                request,
                PlaceOrderResponse.Status.SESSION_DOWN,
                "FIX session for ${session.venue} is not connected",
            )
        } catch (_: PlaceOrderUnknownVenue) {
            return placeRejected(
                request,
                PlaceOrderResponse.Status.UNKNOWN_VENUE,
                "venue '${request.venue}' is not connected by the session manager",
            )
        }

        return when (response) {
            PendingNewCorrelator.Registration.DuplicateInFlight ->
                placeRejected(
                    request,
                    PlaceOrderResponse.Status.DUPLICATE_IN_FLIGHT,
                    "another PlaceOrder for cl_ord_id=${request.clOrdId} is already in flight",
                )
            PendingNewCorrelator.Registration.BackPressure ->
                placeRejected(
                    request,
                    PlaceOrderResponse.Status.SESSION_DOWN,
                    "back_pressure",
                )
            PendingNewCorrelator.Registration.Registered -> {
                val outcome = runBlocking {
                    pendingNewCorrelator.await(session.venue, request.clOrdId, timeout)
                }
                mapOutcome(request, outcome).also {
                    logger.info(
                        "PlaceOrder venue={} clOrdId={} timeoutMs={} status={} venueOrderId={}",
                        session.venue, request.clOrdId, timeout.toMillis(), it.status, it.venueOrderId,
                    )
                }
            }
        }
    }

    private fun resolveTimeout(session: VenueSession, request: PlaceOrderRequest): Duration {
        val override = request.venueAckTimeoutMs
        return if (override > 0) Duration.ofMillis(override.toLong())
        else Duration.ofMillis(session.defaultVenueAckTimeoutMs.toLong())
    }

    private fun mapOutcome(
        request: PlaceOrderRequest,
        outcome: PendingNewCorrelator.Outcome,
    ): PlaceOrderResponse = when (outcome) {
        is PendingNewCorrelator.Outcome.PendingNew -> PlaceOrderResponse.newBuilder()
            .setClOrdId(request.clOrdId)
            .setStatus(PlaceOrderResponse.Status.PENDING_NEW)
            .setVenueOrderId(outcome.venueOrderId)
            .build()
        is PendingNewCorrelator.Outcome.Rejected -> PlaceOrderResponse.newBuilder()
            .setClOrdId(request.clOrdId)
            .setStatus(PlaceOrderResponse.Status.REJECTED)
            .setRejectReason(outcome.reason)
            .build()
        PendingNewCorrelator.Outcome.Timeout -> PlaceOrderResponse.newBuilder()
            .setClOrdId(request.clOrdId)
            .setStatus(PlaceOrderResponse.Status.SESSION_DOWN)
            .setRejectReason("venue did not acknowledge within deadline")
            .build()
    }

    private fun placeRejected(
        request: PlaceOrderRequest,
        status: PlaceOrderResponse.Status,
        reason: String,
    ): PlaceOrderResponse = PlaceOrderResponse.newBuilder()
        .setClOrdId(request.clOrdId)
        .setStatus(status)
        .setRejectReason(reason)
        .build()

    private object PlaceOrderSessionDown : RuntimeException() {
        private fun readResolve(): Any = PlaceOrderSessionDown
    }

    private object PlaceOrderUnknownVenue : RuntimeException() {
        private fun readResolve(): Any = PlaceOrderUnknownVenue
    }
}
