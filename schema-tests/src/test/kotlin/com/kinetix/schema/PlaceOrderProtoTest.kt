package com.kinetix.schema

import com.kinetix.proto.execution.OrderType
import com.kinetix.proto.execution.PlaceOrderRequest
import com.kinetix.proto.execution.PlaceOrderResponse
import com.kinetix.proto.execution.Side
import com.kinetix.proto.execution.TimeInForce
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Pins the wire format of the `PlaceOrder` RPC contract introduced in
 * ADR-0035 phase 4. Every enum (Side, OrderType, TimeInForce, and the nested
 * PlaceOrderResponse.Status) must round-trip via the protobuf binary encoding,
 * and the nested enum's qualified Kotlin name must be the one
 * `position-service`'s `OrderSubmissionService` will reference at compile time.
 *
 * The "nested enum codegen" assertions exist because the risk register flagged
 * nested-enum naming as a code-generator surprise — some generators flatten
 * nested enums into the parent's namespace; we want to fail this test BEFORE
 * the position-service client tries to use the wrong name.
 */
class PlaceOrderProtoTest : FunSpec({

    test("Side enum round-trips every value through the proto encoder") {
        val sides = listOf(Side.BUY, Side.SELL, Side.SIDE_UNSPECIFIED)
        for (side in sides) {
            val req = PlaceOrderRequest.newBuilder().setSide(side).build()
            val bytes = req.toByteArray()
            val decoded = PlaceOrderRequest.parseFrom(bytes)
            decoded.side shouldBe side
        }
    }

    test("OrderType enum round-trips MARKET and LIMIT") {
        val types = listOf(OrderType.MARKET, OrderType.LIMIT, OrderType.ORDER_TYPE_UNSPECIFIED)
        for (orderType in types) {
            val req = PlaceOrderRequest.newBuilder().setOrderType(orderType).build()
            val bytes = req.toByteArray()
            val decoded = PlaceOrderRequest.parseFrom(bytes)
            decoded.orderType shouldBe orderType
        }
    }

    test("TimeInForce enum round-trips every TIF prefix variant") {
        val tifs = listOf(
            TimeInForce.TIF_DAY,
            TimeInForce.TIF_GTC,
            TimeInForce.TIF_IOC,
            TimeInForce.TIF_FOK,
            TimeInForce.TIF_GTD,
            TimeInForce.TIME_IN_FORCE_UNSPECIFIED,
        )
        for (tif in tifs) {
            val req = PlaceOrderRequest.newBuilder().setTimeInForce(tif).build()
            val bytes = req.toByteArray()
            val decoded = PlaceOrderRequest.parseFrom(bytes)
            decoded.timeInForce shouldBe tif
        }
    }

    test("PlaceOrderResponse.Status round-trips every variant including DUPLICATE_IN_FLIGHT") {
        val statuses = listOf(
            PlaceOrderResponse.Status.PENDING_NEW,
            PlaceOrderResponse.Status.REJECTED,
            PlaceOrderResponse.Status.SESSION_DOWN,
            PlaceOrderResponse.Status.UNKNOWN_VENUE,
            PlaceOrderResponse.Status.INVALID_REQUEST,
            PlaceOrderResponse.Status.DUPLICATE_IN_FLIGHT,
            PlaceOrderResponse.Status.STATUS_UNSPECIFIED,
        )
        for (status in statuses) {
            val resp = PlaceOrderResponse.newBuilder().setStatus(status).build()
            val bytes = resp.toByteArray()
            val decoded = PlaceOrderResponse.parseFrom(bytes)
            decoded.status shouldBe status
        }
    }

    test("nested PlaceOrderResponse.Status enum is reachable as a nested type") {
        val pendingNew: PlaceOrderResponse.Status = PlaceOrderResponse.Status.PENDING_NEW
        pendingNew.number shouldBe 1
        pendingNew.name shouldBe "PENDING_NEW"

        val duplicateInFlight: PlaceOrderResponse.Status = PlaceOrderResponse.Status.DUPLICATE_IN_FLIGHT
        duplicateInFlight.number shouldBe 6
        duplicateInFlight.name shouldBe "DUPLICATE_IN_FLIGHT"
    }

    test("PlaceOrderRequest preserves decimal-as-string fields verbatim") {
        val request = PlaceOrderRequest.newBuilder()
            .setClOrdId("5b2a3f1e-1234-4abc-9def-0123456789ab")
            .setVenue("NYSE")
            .setInstrumentId("AAPL")
            .setSide(Side.BUY)
            .setOrderType(OrderType.LIMIT)
            .setQuantity("100")
            .setLimitPrice("150.25")
            .setTimeInForce(TimeInForce.TIF_DAY)
            .setCorrelationId("corr-1")
            .setVenueAckTimeoutMs(0)
            .build()

        val decoded = PlaceOrderRequest.parseFrom(request.toByteArray())

        decoded.clOrdId shouldBe "5b2a3f1e-1234-4abc-9def-0123456789ab"
        decoded.venue shouldBe "NYSE"
        decoded.instrumentId shouldBe "AAPL"
        decoded.quantity shouldBe "100"
        decoded.limitPrice shouldBe "150.25"
        decoded.timeInForce shouldBe TimeInForce.TIF_DAY
        decoded.venueAckTimeoutMs shouldBe 0
    }

    test("PlaceOrderRequest preserves extreme decimal values without overflow") {
        val request = PlaceOrderRequest.newBuilder()
            .setQuantity("0.00000001")
            .setLimitPrice("99999999999999.99")
            .build()

        val decoded = PlaceOrderRequest.parseFrom(request.toByteArray())

        decoded.quantity shouldBe "0.00000001"
        decoded.limitPrice shouldBe "99999999999999.99"
    }

    test("PlaceOrderRequest preserves a negative limit_price verbatim (synthetic instruments)") {
        val request = PlaceOrderRequest.newBuilder()
            .setLimitPrice("-1.00")
            .build()

        val decoded = PlaceOrderRequest.parseFrom(request.toByteArray())

        decoded.limitPrice shouldBe "-1.00"
    }

    test("PlaceOrderRequest defaults: empty limit_price for MARKET, empty expires_at_iso for non-GTD") {
        val request = PlaceOrderRequest.newBuilder()
            .setSide(Side.BUY)
            .setOrderType(OrderType.MARKET)
            .setQuantity("100")
            .setTimeInForce(TimeInForce.TIF_IOC)
            .build()

        val decoded = PlaceOrderRequest.parseFrom(request.toByteArray())

        decoded.limitPrice shouldBe ""
        decoded.expiresAtIso shouldBe ""
        decoded.correlationId shouldBe ""
    }

    test("PlaceOrderResponse populates venue_order_id only on PENDING_NEW") {
        val ack = PlaceOrderResponse.newBuilder()
            .setClOrdId("c1")
            .setVenueOrderId("V-9876")
            .setStatus(PlaceOrderResponse.Status.PENDING_NEW)
            .build()
        val rejected = PlaceOrderResponse.newBuilder()
            .setClOrdId("c2")
            .setStatus(PlaceOrderResponse.Status.REJECTED)
            .setRejectReason("Unknown instrument")
            .build()

        val decodedAck = PlaceOrderResponse.parseFrom(ack.toByteArray())
        decodedAck.status shouldBe PlaceOrderResponse.Status.PENDING_NEW
        decodedAck.venueOrderId shouldBe "V-9876"
        decodedAck.rejectReason shouldBe ""

        val decodedReject = PlaceOrderResponse.parseFrom(rejected.toByteArray())
        decodedReject.status shouldBe PlaceOrderResponse.Status.REJECTED
        decodedReject.venueOrderId shouldBe ""
        decodedReject.rejectReason shouldBe "Unknown instrument"
    }

    test("Side / OrderType / TimeInForce remain in distinct namespaces — no value collisions") {
        Side.BUY shouldNotBe OrderType.MARKET
        OrderType.LIMIT shouldNotBe TimeInForce.TIF_DAY
    }

    test("per-call venue_ack_timeout_ms override is preserved") {
        val request = PlaceOrderRequest.newBuilder()
            .setVenueAckTimeoutMs(5000)
            .build()

        val decoded = PlaceOrderRequest.parseFrom(request.toByteArray())

        decoded.venueAckTimeoutMs shouldBe 5000
    }
})
