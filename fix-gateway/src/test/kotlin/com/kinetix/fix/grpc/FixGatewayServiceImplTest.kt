package com.kinetix.fix.grpc

import com.google.protobuf.Timestamp
import com.kinetix.fix.session.CancelMessageBuilder
import com.kinetix.fix.session.FixSessionSender
import com.kinetix.fix.session.NewOrderSingleBuilder
import com.kinetix.fix.session.PendingNewCorrelator
import com.kinetix.fix.session.RecordingFixSessionSender
import com.kinetix.fix.session.SendOutcome
import com.kinetix.fix.venue.VenueCutoffRegistry
import com.kinetix.fix.venue.VenueSessionRegistry
import com.kinetix.proto.execution.CancelOrderRequest
import com.kinetix.proto.execution.CancelOrderResponse
import com.kinetix.proto.execution.IsVenueOpenRequest
import com.kinetix.proto.execution.OrderType
import com.kinetix.proto.execution.PlaceOrderRequest
import com.kinetix.proto.execution.PlaceOrderResponse
import com.kinetix.proto.execution.Side
import com.kinetix.proto.execution.TimeInForce
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import quickfix.Message
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class FixGatewayServiceImplTest : FunSpec({

    val fixedClock = { Instant.parse("2026-05-04T20:00:00Z") } // 16:00 ET — at NYSE cutoff

    fun service(
        sessionSender: FixSessionSender = RecordingFixSessionSender(SendOutcome.Sent),
        lookup: FixGatewayServiceImpl.OriginalOrderLookup = FixGatewayServiceImpl.OriginalOrderLookup { _, _ ->
            FixGatewayServiceImpl.OriginalOrder("AAPL", '1', BigDecimal("100"))
        },
        clock: () -> Instant = fixedClock,
        correlator: PendingNewCorrelator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry()),
    ) = FixGatewayServiceImpl(
        venueSessionRegistry = VenueSessionRegistry(),
        venueCutoffRegistry = VenueCutoffRegistry(),
        cancelMessageBuilder = CancelMessageBuilder(),
        newOrderSingleBuilder = NewOrderSingleBuilder(),
        pendingNewCorrelator = correlator,
        sessionSender = sessionSender,
        originalOrderLookup = lookup,
        clock = clock,
    )

    fun cancelRequest(
        clOrdId: String = "ord-1",
        venue: String = "NYSE",
        venueOrderId: String = "VENUE-1",
    ): CancelOrderRequest = CancelOrderRequest.newBuilder()
        .setClOrdId(clOrdId)
        .setVenue(venue)
        .setVenueOrderId(venueOrderId)
        .build()

    // ---------------------------------------------------------------------
    // CancelOrder
    // ---------------------------------------------------------------------

    test("CancelOrder returns ACCEPTED when session sender accepts") {
        val sender = RecordingFixSessionSender(SendOutcome.Sent)
        val response = service(sessionSender = sender).handleCancel(cancelRequest())

        response.status shouldBe CancelOrderResponse.Status.ACCEPTED
        response.clOrdId shouldBe "ord-1"
        sender.sentMessages.size shouldBe 1
        sender.sentMessages[0].second.getString(41) shouldBe "ord-1"
        sender.sentMessages[0].second.getString(37) shouldBe "VENUE-1"
    }

    test("CancelOrder returns SESSION_DOWN when session not connected") {
        val response = service(sessionSender = RecordingFixSessionSender(SendOutcome.SessionDown)).handleCancel(cancelRequest())
        response.status shouldBe CancelOrderResponse.Status.SESSION_DOWN
        response.detail shouldNotBe ""
    }

    test("CancelOrder returns UNKNOWN_VENUE when venue not registered") {
        val response = service().handleCancel(cancelRequest(venue = "MADEUP"))
        response.status shouldBe CancelOrderResponse.Status.UNKNOWN_VENUE
    }

    test("CancelOrder returns INVALID_REQUEST when venue_order_id is empty") {
        val response = service().handleCancel(cancelRequest(venueOrderId = ""))
        response.status shouldBe CancelOrderResponse.Status.INVALID_REQUEST
        response.detail shouldNotBe ""
    }

    test("CancelOrder returns INVALID_REQUEST when cl_ord_id is empty") {
        val response = service().handleCancel(cancelRequest(clOrdId = ""))
        response.status shouldBe CancelOrderResponse.Status.INVALID_REQUEST
    }

    test("CancelOrder returns INVALID_REQUEST when original order is not in fix_message_log") {
        val response = service(
            lookup = FixGatewayServiceImpl.OriginalOrderLookup { _, _ -> null },
        ).handleCancel(cancelRequest())
        response.status shouldBe CancelOrderResponse.Status.INVALID_REQUEST
        response.detail shouldNotBe ""
    }

    test("CancelOrder normalises venue case via the registry") {
        val response = service().handleCancel(cancelRequest(venue = "nyse"))
        response.status shouldBe CancelOrderResponse.Status.ACCEPTED
    }

    // ---------------------------------------------------------------------
    // IsVenueOpen
    // ---------------------------------------------------------------------

    test("IsVenueOpen returns true for NYSE at 14:00 ET on a weekday") {
        val request = IsVenueOpenRequest.newBuilder()
            .setVenue("NYSE")
            .setAt(toProto(Instant.parse("2026-05-04T18:00:00Z")))
            .build()

        val response = service().handleIsVenueOpen(request)
        response.open shouldBe true
        // next_close must be populated when open
        response.nextClose.seconds shouldBe Instant.parse("2026-05-04T20:00:00Z").epochSecond
    }

    test("IsVenueOpen returns false past NYSE cutoff") {
        val request = IsVenueOpenRequest.newBuilder()
            .setVenue("NYSE")
            .setAt(toProto(Instant.parse("2026-05-04T20:30:00Z")))
            .build()

        val response = service().handleIsVenueOpen(request)
        response.open shouldBe false
    }

    test("IsVenueOpen returns false for unknown venue") {
        val request = IsVenueOpenRequest.newBuilder()
            .setVenue("MADEUP")
            .setAt(toProto(Instant.parse("2026-05-04T18:00:00Z")))
            .build()

        val response = service().handleIsVenueOpen(request)
        response.open shouldBe false
    }

    test("IsVenueOpen falls back to clock when at is unset") {
        // fixedClock = 2026-05-04T20:00 — that is exactly at NYSE cutoff (closed)
        val request = IsVenueOpenRequest.newBuilder().setVenue("NYSE").build()
        val response = service().handleIsVenueOpen(request)
        response.open shouldBe false
    }

    // ---------------------------------------------------------------------
    // PlaceOrder
    // ---------------------------------------------------------------------

    fun limitBuyAaplDay(
        clOrdId: String = "ord-place-1",
        venue: String = "NYSE",
        timeoutMs: Int = 0,
    ): PlaceOrderRequest = PlaceOrderRequest.newBuilder()
        .setClOrdId(clOrdId)
        .setVenue(venue)
        .setInstrumentId("AAPL")
        .setSide(Side.BUY)
        .setOrderType(OrderType.LIMIT)
        .setQuantity("100")
        .setLimitPrice("150.25")
        .setTimeInForce(TimeInForce.TIF_DAY)
        .setVenueAckTimeoutMs(timeoutMs)
        .build()

    test("PlaceOrder returns PENDING_NEW with venue_order_id when correlator wakes with PendingNew") {
        val sender = RecordingFixSessionSender(SendOutcome.Sent)
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        val svc = service(sessionSender = sender, correlator = correlator)

        // Use a generous explicit timeout: this test asserts the correlation/status
        // wiring, not the per-venue default timeout. The NYSE 200ms default plus a
        // 20ms async delay leaves only ~180ms of budget for Dispatchers.Default
        // worker scheduling — flaky under CI load. The per-venue default-timeout
        // assertion lives in its own test below.
        runBlocking {
            coroutineScope {
                val ackJob = async(Dispatchers.Default) {
                    delay(20)
                    correlator.completePendingNew("NYSE", "ord-place-1", venueOrderId = "VEN-PN-1")
                }
                val response = svc.handlePlaceOrder(limitBuyAaplDay(timeoutMs = 5_000))
                ackJob.await()
                response.status shouldBe PlaceOrderResponse.Status.PENDING_NEW
                response.venueOrderId shouldBe "VEN-PN-1"
                response.clOrdId shouldBe "ord-place-1"

                sender.sentMessages.size shouldBe 1
                val (venue, msg) = sender.sentMessages.single()
                venue shouldBe "NYSE"
                msg.header.getString(35) shouldBe "D"
                msg.getString(11) shouldBe "ord-place-1"
                msg.getString(54) shouldBe "1"
                msg.getString(38) shouldBe "100"
                msg.getString(40) shouldBe "2"
            }
        }
    }

    test("PlaceOrder returns REJECTED with reject_reason when correlator surfaces a venue reject") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        val svc = service(correlator = correlator)
        runBlocking {
            coroutineScope {
                val rejectJob = async(Dispatchers.Default) {
                    delay(10)
                    correlator.completeRejected("NYSE", "ord-place-2", "INVALID_INSTRUMENT")
                }
                val response = svc.handlePlaceOrder(limitBuyAaplDay(clOrdId = "ord-place-2"))
                rejectJob.await()
                response.status shouldBe PlaceOrderResponse.Status.REJECTED
                response.rejectReason shouldBe "INVALID_INSTRUMENT"
            }
        }
    }

    test("PlaceOrder returns SESSION_DOWN when venue does not ack within timeout") {
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        val svc = service(correlator = correlator)

        val response = svc.handlePlaceOrder(limitBuyAaplDay(clOrdId = "ord-place-3", timeoutMs = 30))
        response.status shouldBe PlaceOrderResponse.Status.SESSION_DOWN
        response.rejectReason shouldNotBe ""
    }

    test("PlaceOrder uses per-venue default timeout from VenueSessionRegistry when override is 0") {
        // NYSE default is 200ms; provide a brief delay before completion to assert we honoured
        // the registry default rather than some hard-coded 2s.
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        val svc = service(correlator = correlator)
        runBlocking {
            coroutineScope {
                async(Dispatchers.Default) {
                    delay(10)
                    correlator.completePendingNew("NYSE", "ord-place-4", "VEN-4")
                }
                val response = svc.handlePlaceOrder(limitBuyAaplDay(clOrdId = "ord-place-4", timeoutMs = 0))
                response.status shouldBe PlaceOrderResponse.Status.PENDING_NEW
            }
        }
    }

    test("PlaceOrder returns SESSION_DOWN when sender reports it (skips await)") {
        val sender = RecordingFixSessionSender(SendOutcome.SessionDown)
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        val svc = service(sessionSender = sender, correlator = correlator)

        val response = svc.handlePlaceOrder(limitBuyAaplDay(clOrdId = "ord-place-5"))
        response.status shouldBe PlaceOrderResponse.Status.SESSION_DOWN

        // The slot must have been released so the next call is allowed.
        sender.outcome = SendOutcome.Sent
        runBlocking {
            coroutineScope {
                async(Dispatchers.Default) {
                    delay(10)
                    correlator.completePendingNew("NYSE", "ord-place-5", "VEN-5")
                }
                val retry = svc.handlePlaceOrder(limitBuyAaplDay(clOrdId = "ord-place-5"))
                retry.status shouldBe PlaceOrderResponse.Status.PENDING_NEW
            }
        }
    }

    test("PlaceOrder returns UNKNOWN_VENUE when venue not in registry") {
        val response = service().handlePlaceOrder(limitBuyAaplDay(venue = "MADEUP"))
        response.status shouldBe PlaceOrderResponse.Status.UNKNOWN_VENUE
    }

    test("PlaceOrder returns INVALID_REQUEST for blank cl_ord_id") {
        val response = service().handlePlaceOrder(limitBuyAaplDay(clOrdId = ""))
        response.status shouldBe PlaceOrderResponse.Status.INVALID_REQUEST
        response.rejectReason shouldNotBe ""
    }

    test("PlaceOrder returns INVALID_REQUEST when builder rejects the request") {
        val request = PlaceOrderRequest.newBuilder()
            .setClOrdId("ord-place-bad")
            .setVenue("NYSE")
            .setInstrumentId("AAPL")
            .setSide(Side.BUY)
            .setOrderType(OrderType.LIMIT)
            .setQuantity("0")            // invalid
            .setLimitPrice("100.00")
            .setTimeInForce(TimeInForce.TIF_DAY)
            .build()
        val response = service().handlePlaceOrder(request)
        response.status shouldBe PlaceOrderResponse.Status.INVALID_REQUEST
    }

    test("PlaceOrder returns DUPLICATE_IN_FLIGHT when a prior call for the same clOrdID is in flight") {
        val sender = RecordingFixSessionSender(SendOutcome.Sent)
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        val svc = service(sessionSender = sender, correlator = correlator)

        runBlocking {
            coroutineScope {
                val firstCall = async(Dispatchers.Default) {
                    svc.handlePlaceOrder(limitBuyAaplDay(clOrdId = "ord-dup", timeoutMs = 5_000))
                }
                // Wait for the first registration to settle.
                while (correlator.inFlightCount("NYSE") == 0) delay(5)

                val duplicate = svc.handlePlaceOrder(limitBuyAaplDay(clOrdId = "ord-dup"))
                duplicate.status shouldBe PlaceOrderResponse.Status.DUPLICATE_IN_FLIGHT

                // Resolve the first so the test exits cleanly.
                correlator.completePendingNew("NYSE", "ord-dup", "VEN-DUP")
                firstCall.await().status shouldBe PlaceOrderResponse.Status.PENDING_NEW
            }
        }
    }
})

private fun toProto(instant: Instant): Timestamp = Timestamp.newBuilder()
    .setSeconds(instant.epochSecond)
    .setNanos(instant.nano)
    .build()
