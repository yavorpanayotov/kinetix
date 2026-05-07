package com.kinetix.fix.grpc

import com.google.protobuf.Timestamp
import com.kinetix.fix.session.CancelMessageBuilder
import com.kinetix.fix.session.FixSessionSender
import com.kinetix.fix.session.RecordingFixSessionSender
import com.kinetix.fix.session.SendOutcome
import com.kinetix.fix.venue.VenueCutoffRegistry
import com.kinetix.fix.venue.VenueSessionRegistry
import com.kinetix.proto.execution.CancelOrderRequest
import com.kinetix.proto.execution.CancelOrderResponse
import com.kinetix.proto.execution.IsVenueOpenRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import quickfix.Message
import java.math.BigDecimal
import java.time.Instant

class FixGatewayServiceImplTest : FunSpec({

    val fixedClock = { Instant.parse("2026-05-04T20:00:00Z") } // 16:00 ET — at NYSE cutoff

    fun service(
        sessionSender: FixSessionSender = RecordingFixSessionSender(SendOutcome.Sent),
        lookup: FixGatewayServiceImpl.OriginalOrderLookup = FixGatewayServiceImpl.OriginalOrderLookup { _, _ ->
            FixGatewayServiceImpl.OriginalOrder("AAPL", '1', BigDecimal("100"))
        },
        clock: () -> Instant = fixedClock,
    ) = FixGatewayServiceImpl(
        venueSessionRegistry = VenueSessionRegistry(),
        venueCutoffRegistry = VenueCutoffRegistry(),
        cancelMessageBuilder = CancelMessageBuilder(),
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
})

private fun toProto(instant: Instant): Timestamp = Timestamp.newBuilder()
    .setSeconds(instant.epochSecond)
    .setNanos(instant.nano)
    .build()
