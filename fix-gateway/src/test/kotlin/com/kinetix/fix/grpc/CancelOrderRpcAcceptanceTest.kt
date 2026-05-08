package com.kinetix.fix.grpc

import com.kinetix.fix.session.CancelMessageBuilder
import com.kinetix.fix.session.FixSessionSender
import com.kinetix.fix.session.NewOrderSingleBuilder
import com.kinetix.fix.session.PendingNewCorrelator
import com.kinetix.fix.session.RecordingFixSessionSender
import com.kinetix.fix.session.SendOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.kinetix.fix.venue.VenueCutoffRegistry
import com.kinetix.fix.venue.VenueSessionRegistry
import com.kinetix.proto.execution.CancelOrderRequest
import com.kinetix.proto.execution.CancelOrderResponse
import com.kinetix.proto.execution.FixGatewayGrpc
import io.grpc.ManagedChannelBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import quickfix.Message
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Plan 2.13: boots the FixGatewayServer with the FixGatewayServiceImpl bound,
 * opens a real gRPC channel, sends CancelOrderRequest, asserts the response
 * carries the expected status and that the recording sender saw a 35=F.
 *
 * Scoped to behaviour reachable without a live FIX counterparty — full
 * QuickFIX/J in-memory acceptor coverage (FIX session protocol edges, mass
 * cancel on disconnect, durability across restart) lands in a follow-on commit
 * once a vendor-tested QuickFIX/J test fixture is wired.
 */
class CancelOrderRpcAcceptanceTest : FunSpec({

    val fixedClock = { Instant.parse("2026-05-04T20:00:00Z") }

    fun bootService(
        sender: FixSessionSender = RecordingFixSessionSender(SendOutcome.Sent),
        knownOrders: Map<String, FixGatewayServiceImpl.OriginalOrder> = mapOf(
            "ord-1" to FixGatewayServiceImpl.OriginalOrder("AAPL", '1', BigDecimal("100")),
        ),
    ): FixGatewayServiceImpl = FixGatewayServiceImpl(
        venueSessionRegistry = VenueSessionRegistry(),
        venueCutoffRegistry = VenueCutoffRegistry(),
        cancelMessageBuilder = CancelMessageBuilder(),
        newOrderSingleBuilder = NewOrderSingleBuilder(),
        pendingNewCorrelator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry()),
        sessionSender = sender,
        originalOrderLookup = { _, clOrdId -> knownOrders[clOrdId] },
        clock = fixedClock,
    )

    fun withChannel(
        service: FixGatewayServiceImpl,
        block: (FixGatewayGrpc.FixGatewayBlockingStub) -> Unit,
    ) {
        val server = FixGatewayServer(port = 0, services = listOf(service)).start()
        val channel = ManagedChannelBuilder
            .forAddress("localhost", server.boundPort())
            .usePlaintext()
            .build()
        try {
            val stub = FixGatewayGrpc.newBlockingStub(channel)
            block(stub)
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
            server.stop()
        }
    }

    test("ACCEPTED when session sender accepts and the FIX 35=F lands at the recorder") {
        val sender = RecordingFixSessionSender(SendOutcome.Sent)
        withChannel(bootService(sender)) { stub ->
            val response = stub.cancelOrder(
                CancelOrderRequest.newBuilder()
                    .setClOrdId("ord-1")
                    .setVenue("NYSE")
                    .setVenueOrderId("VENUE-1")
                    .build()
            )
            response.status shouldBe CancelOrderResponse.Status.ACCEPTED
            response.clOrdId shouldBe "ord-1"

            sender.sentMessages.size shouldBe 1
            val (venue, msg) = sender.sentMessages.single()
            venue shouldBe "NYSE"
            msg.header.getString(35) shouldBe "F"
            msg.getString(41) shouldBe "ord-1"        // OrigClOrdID
            msg.getString(37) shouldBe "VENUE-1"      // OrderID
            msg.getString(54) shouldBe "1"             // Side
            msg.getString(38) shouldBe "100"           // OrderQty
            msg.getString(11) shouldContain "ord-1-cxl-" // newly minted ClOrdID
        }
    }

    test("SESSION_DOWN when session sender reports it") {
        withChannel(bootService(RecordingFixSessionSender(SendOutcome.SessionDown))) { stub ->
            val response = stub.cancelOrder(
                CancelOrderRequest.newBuilder()
                    .setClOrdId("ord-1")
                    .setVenue("NYSE")
                    .setVenueOrderId("VENUE-1")
                    .build()
            )
            response.status shouldBe CancelOrderResponse.Status.SESSION_DOWN
        }
    }

    test("UNKNOWN_VENUE for venue not in the registry") {
        withChannel(bootService()) { stub ->
            val response = stub.cancelOrder(
                CancelOrderRequest.newBuilder()
                    .setClOrdId("ord-1")
                    .setVenue("MADEUP")
                    .setVenueOrderId("VENUE-1")
                    .build()
            )
            response.status shouldBe CancelOrderResponse.Status.UNKNOWN_VENUE
        }
    }

    test("INVALID_REQUEST when venue_order_id is empty") {
        withChannel(bootService()) { stub ->
            val response = stub.cancelOrder(
                CancelOrderRequest.newBuilder()
                    .setClOrdId("ord-1")
                    .setVenue("NYSE")
                    .build()
            )
            response.status shouldBe CancelOrderResponse.Status.INVALID_REQUEST
        }
    }

    test("INVALID_REQUEST when fix_message_log has no record of the original 35=D") {
        withChannel(bootService(knownOrders = emptyMap())) { stub ->
            val response = stub.cancelOrder(
                CancelOrderRequest.newBuilder()
                    .setClOrdId("ord-1")
                    .setVenue("NYSE")
                    .setVenueOrderId("VENUE-1")
                    .build()
            )
            response.status shouldBe CancelOrderResponse.Status.INVALID_REQUEST
        }
    }
})

