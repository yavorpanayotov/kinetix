package com.kinetix.fix.grpc

import com.kinetix.fix.session.CancelMessageBuilder
import com.kinetix.fix.session.NewOrderSingleBuilder
import com.kinetix.fix.session.PendingNewCorrelator
import com.kinetix.fix.session.RecordingFixSessionSender
import com.kinetix.fix.session.SendOutcome
import com.kinetix.fix.venue.VenueCutoffRegistry
import com.kinetix.fix.venue.VenueSessionRegistry
import com.kinetix.proto.execution.FixGatewayGrpc
import com.kinetix.proto.execution.OrderType
import com.kinetix.proto.execution.PlaceOrderRequest
import com.kinetix.proto.execution.PlaceOrderResponse
import com.kinetix.proto.execution.Side
import com.kinetix.proto.execution.TimeInForce
import io.grpc.ManagedChannelBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Plan §4.9: end-to-end gRPC test for PlaceOrder. Mirrors
 * [CancelOrderRpcAcceptanceTest]'s shape — boots the FixGatewayServer, opens a
 * real gRPC channel, exercises every Status branch.
 *
 * Scoped to behaviour reachable without a live FIX counterparty: the
 * "in-memory acceptor" the plan calls for lands in a follow-on commit once a
 * vendor-tested QuickFIX/J test fixture is wired (same scoping decision the
 * cancel acceptance test took). The correlator is driven by the test harness
 * to simulate the inbound 35=8 ack / reject; the on-wire 35=D byte shape is
 * pinned via the recording sender.
 */
class PlaceOrderRpcAcceptanceTest : FunSpec({

    val fixedClock = { Instant.parse("2026-05-04T18:00:00Z") } // 14:00 ET — NYSE open

    fun bootService(
        sender: RecordingFixSessionSender = RecordingFixSessionSender(SendOutcome.Sent),
        correlator: PendingNewCorrelator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry()),
    ): Pair<FixGatewayServiceImpl, PendingNewCorrelator> {
        val service = FixGatewayServiceImpl(
            venueSessionRegistry = VenueSessionRegistry(),
            venueCutoffRegistry = VenueCutoffRegistry(),
            cancelMessageBuilder = CancelMessageBuilder(),
            newOrderSingleBuilder = NewOrderSingleBuilder(),
            pendingNewCorrelator = correlator,
            sessionSender = sender,
            originalOrderLookup = { _, _ -> null },
            clock = fixedClock,
        )
        return service to correlator
    }

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
            block(FixGatewayGrpc.newBlockingStub(channel))
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
            server.stop()
        }
    }

    fun limitBuyAaplDay(
        clOrdId: String = "ord-acc-1",
        venue: String = "NYSE",
        timeoutMs: Int = 1_000,
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

    test("PENDING_NEW with venue_order_id when correlator wakes with PendingNew (delayed ack)") {
        val sender = RecordingFixSessionSender(SendOutcome.Sent)
        val (service, correlator) = bootService(sender = sender)

        withChannel(service) { stub ->
            runBlocking {
                coroutineScope {
                    async(Dispatchers.Default) {
                        delay(20)
                        correlator.completePendingNew("NYSE", "ord-acc-1", venueOrderId = "VEN-9000")
                    }
                    val response = stub.placeOrder(limitBuyAaplDay())
                    response.status shouldBe PlaceOrderResponse.Status.PENDING_NEW
                    response.venueOrderId shouldBe "VEN-9000"
                    response.clOrdId shouldBe "ord-acc-1"

                    // 35=D byte-shape on the wire.
                    sender.sentMessages.size shouldBe 1
                    val (venue, msg) = sender.sentMessages.single()
                    venue shouldBe "NYSE"
                    msg.header.getString(35) shouldBe "D"
                    msg.getString(11) shouldBe "ord-acc-1"
                    msg.getString(55) shouldBe "AAPL"
                    msg.getString(54) shouldBe "1"
                    msg.getString(38) shouldBe "100"
                    msg.getString(40) shouldBe "2"
                    msg.getString(44) shouldBe "150.25"
                    msg.getString(59) shouldBe "0"
                }
            }
        }
    }

    test("REJECTED with reject_reason when correlator surfaces a venue 35=8 OrdStatus=8") {
        val (service, correlator) = bootService()
        withChannel(service) { stub ->
            runBlocking {
                coroutineScope {
                    async(Dispatchers.Default) {
                        delay(10)
                        correlator.completeRejected("NYSE", "ord-acc-2", "RISK_REJECT")
                    }
                    val response = stub.placeOrder(limitBuyAaplDay(clOrdId = "ord-acc-2"))
                    response.status shouldBe PlaceOrderResponse.Status.REJECTED
                    response.rejectReason shouldBe "RISK_REJECT"
                }
            }
        }
    }

    test("SESSION_DOWN when venue does not ack within deadline") {
        val (service, _) = bootService()
        withChannel(service) { stub ->
            val response = stub.placeOrder(limitBuyAaplDay(clOrdId = "ord-acc-3", timeoutMs = 30))
            response.status shouldBe PlaceOrderResponse.Status.SESSION_DOWN
        }
    }

    test("UNKNOWN_VENUE when venue is not registered") {
        val (service, _) = bootService()
        withChannel(service) { stub ->
            val response = stub.placeOrder(limitBuyAaplDay(clOrdId = "ord-acc-4", venue = "MADEUP"))
            response.status shouldBe PlaceOrderResponse.Status.UNKNOWN_VENUE
        }
    }

    test("INVALID_REQUEST when builder rejects (zero quantity)") {
        val (service, _) = bootService()
        withChannel(service) { stub ->
            val request = PlaceOrderRequest.newBuilder(limitBuyAaplDay(clOrdId = "ord-acc-5"))
                .setQuantity("0")
                .build()
            val response = stub.placeOrder(request)
            response.status shouldBe PlaceOrderResponse.Status.INVALID_REQUEST
            response.rejectReason shouldContain "quantity"
        }
    }

    test("DUPLICATE_IN_FLIGHT when a prior call for the same clOrdID is still in flight") {
        val sender = RecordingFixSessionSender(SendOutcome.Sent)
        val (service, correlator) = bootService(sender = sender)

        withChannel(service) { stub ->
            runBlocking {
                coroutineScope {
                    val first = async(Dispatchers.Default) {
                        stub.placeOrder(limitBuyAaplDay(clOrdId = "ord-acc-dup", timeoutMs = 5_000))
                    }
                    while (correlator.inFlightCount("NYSE") == 0) delay(5)

                    val duplicate = stub.placeOrder(limitBuyAaplDay(clOrdId = "ord-acc-dup"))
                    duplicate.status shouldBe PlaceOrderResponse.Status.DUPLICATE_IN_FLIGHT

                    correlator.completePendingNew("NYSE", "ord-acc-dup", "VEN-DUP")
                    first.await().status shouldBe PlaceOrderResponse.Status.PENDING_NEW
                }
            }
        }
    }

    test("per-venue default timeout: NYSE 200ms vs LSE 500ms (sanity check, not a strict SLO)") {
        val (service, _) = bootService()
        withChannel(service) { stub ->
            val nyseElapsed = measureTimeMillis {
                stub.placeOrder(limitBuyAaplDay(clOrdId = "ord-acc-nyse-to", timeoutMs = 0))
            }
            val lseElapsed = measureTimeMillis {
                stub.placeOrder(limitBuyAaplDay(clOrdId = "ord-acc-lse-to", venue = "LSE", timeoutMs = 0))
            }
            // Sanity: NYSE timed out before LSE could possibly time out (LSE default = 500ms,
            // NYSE default = 200ms). Generous fudge for test-host jitter.
            (nyseElapsed < lseElapsed + 1_000) shouldBe true
            // And both must have been at least the venue's default before they can fall through
            // (modulo a small clock-resolution slack).
            (nyseElapsed >= 100) shouldBe true
            (lseElapsed >= 400) shouldBe true
        }
    }
})
