package com.kinetix.position.fix

import com.kinetix.common.execution.PlaceOrderStatus
import com.kinetix.common.model.Side
import com.kinetix.proto.execution.FixGatewayGrpc
import com.kinetix.proto.execution.PlaceOrderRequest
import com.kinetix.proto.execution.PlaceOrderResponse
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit
import com.kinetix.proto.execution.OrderType as ProtoOrderType
import com.kinetix.proto.execution.Side as ProtoSide
import com.kinetix.proto.execution.TimeInForce as ProtoTimeInForce

/**
 * Plan 4.10: in-JVM gRPC stub-server pattern (per CLAUDE.md). Boots a fake
 * `FixGatewayImplBase` on a random port and exercises [GrpcFixGatewayClient]
 * against it; asserts the proto mapping and that each [PlaceOrderResponse.Status]
 * surfaces as the matching [PlaceOrderStatus] on the result.
 */
class GrpcFixGatewayClientAcceptanceTest : FunSpec({

    test("PENDING_NEW propagates venueOrderId on the result") {
        runPlaceOrderFakeServer(stubBehaviour = PlaceOrderStubBehaviour.PendingNew(venueOrderId = "NYSE-OID-42")) { stub ->
            val client = GrpcFixGatewayClient(stub.channel)
            val result = runBlocking {
                client.placeOrder(
                    clOrdId = "ord-1",
                    venue = "NYSE",
                    instrumentId = "AAPL",
                    side = Side.BUY,
                    orderType = "LIMIT",
                    quantity = BigDecimal("100"),
                    limitPrice = BigDecimal("150.25"),
                    timeInForce = "DAY",
                    correlationId = "corr-1",
                )
            }
            result.status shouldBe PlaceOrderStatus.PENDING_NEW
            result.venueOrderId shouldBe "NYSE-OID-42"
            result.clOrdId shouldBe "ord-1"

            stub.received shouldHaveSize 1
            val req = stub.received[0]
            req.clOrdId shouldBe "ord-1"
            req.venue shouldBe "NYSE"
            req.instrumentId shouldBe "AAPL"
            req.side shouldBe ProtoSide.BUY
            req.orderType shouldBe ProtoOrderType.LIMIT
            req.quantity shouldBe "100"
            req.limitPrice shouldBe "150.25"
            req.timeInForce shouldBe ProtoTimeInForce.TIF_DAY
            req.correlationId shouldBe "corr-1"
        }
    }

    test("MARKET order omits limit_price on the wire") {
        runPlaceOrderFakeServer(stubBehaviour = PlaceOrderStubBehaviour.PendingNew("V1")) { stub ->
            val client = GrpcFixGatewayClient(stub.channel)
            runBlocking {
                client.placeOrder(
                    clOrdId = "ord-mkt",
                    venue = "NYSE",
                    instrumentId = "AAPL",
                    side = Side.SELL,
                    orderType = "MARKET",
                    quantity = BigDecimal("50"),
                    limitPrice = null,
                    timeInForce = "IOC",
                )
            }
            stub.received[0].limitPrice shouldBe ""
            stub.received[0].orderType shouldBe ProtoOrderType.MARKET
            stub.received[0].timeInForce shouldBe ProtoTimeInForce.TIF_IOC
            stub.received[0].side shouldBe ProtoSide.SELL
        }
    }

    test("GTD order forwards expiresAtIso") {
        runPlaceOrderFakeServer(stubBehaviour = PlaceOrderStubBehaviour.PendingNew("V1")) { stub ->
            val expiry = Instant.parse("2026-06-01T20:00:00Z")
            runBlocking {
                GrpcFixGatewayClient(stub.channel).placeOrder(
                    clOrdId = "ord-gtd", venue = "NYSE", instrumentId = "AAPL",
                    side = Side.BUY, orderType = "LIMIT", quantity = BigDecimal("10"),
                    limitPrice = BigDecimal("100"), timeInForce = "GTD",
                    expiresAt = expiry,
                )
            }
            stub.received[0].timeInForce shouldBe ProtoTimeInForce.TIF_GTD
            stub.received[0].expiresAtIso shouldBe "2026-06-01T20:00:00Z"
        }
    }

    test("REJECTED carries the venue's rejectReason on the result") {
        runPlaceOrderFakeServer(stubBehaviour = PlaceOrderStubBehaviour.Rejected("MARKET_HALT")) { stub ->
            val result = runBlocking {
                GrpcFixGatewayClient(stub.channel).placeOrder(
                    clOrdId = "ord-rej", venue = "NYSE", instrumentId = "AAPL",
                    side = Side.BUY, orderType = "LIMIT", quantity = BigDecimal("1"),
                    limitPrice = BigDecimal("100"), timeInForce = "DAY",
                )
            }
            result.status shouldBe PlaceOrderStatus.REJECTED
            result.rejectReason shouldBe "MARKET_HALT"
            result.venueOrderId shouldBe null
        }
    }

    test("SESSION_DOWN status surfaces as PlaceOrderStatus.SESSION_DOWN") {
        runPlaceOrderFakeServer(stubBehaviour = PlaceOrderStubBehaviour.SessionDown) { stub ->
            val result = runBlocking {
                GrpcFixGatewayClient(stub.channel).placeOrder(
                    "ord-sd", "NYSE", "AAPL", Side.BUY, "MARKET", BigDecimal("1"),
                    null, "DAY",
                )
            }
            result.status shouldBe PlaceOrderStatus.SESSION_DOWN
        }
    }

    test("UNKNOWN_VENUE status surfaces as PlaceOrderStatus.UNKNOWN_VENUE") {
        runPlaceOrderFakeServer(stubBehaviour = PlaceOrderStubBehaviour.UnknownVenue) { stub ->
            val result = runBlocking {
                GrpcFixGatewayClient(stub.channel).placeOrder(
                    "ord-uv", "MADEUP", "AAPL", Side.BUY, "MARKET", BigDecimal("1"),
                    null, "DAY",
                )
            }
            result.status shouldBe PlaceOrderStatus.UNKNOWN_VENUE
        }
    }

    test("INVALID_REQUEST status surfaces as PlaceOrderStatus.INVALID_REQUEST") {
        runPlaceOrderFakeServer(stubBehaviour = PlaceOrderStubBehaviour.InvalidRequest) { stub ->
            val result = runBlocking {
                GrpcFixGatewayClient(stub.channel).placeOrder(
                    "ord-ir", "NYSE", "AAPL", Side.BUY, "MARKET", BigDecimal("1"),
                    null, "DAY",
                )
            }
            result.status shouldBe PlaceOrderStatus.INVALID_REQUEST
        }
    }

    test("DUPLICATE_IN_FLIGHT status surfaces as PlaceOrderStatus.DUPLICATE_IN_FLIGHT") {
        runPlaceOrderFakeServer(stubBehaviour = PlaceOrderStubBehaviour.DuplicateInFlight) { stub ->
            val result = runBlocking {
                GrpcFixGatewayClient(stub.channel).placeOrder(
                    "ord-dup", "NYSE", "AAPL", Side.BUY, "MARKET", BigDecimal("1"),
                    null, "DAY",
                )
            }
            result.status shouldBe PlaceOrderStatus.DUPLICATE_IN_FLIGHT
        }
    }

    test("client deadline expiry surfaces as PlaceOrderStatus.DEADLINE_EXCEEDED") {
        runPlaceOrderFakeServer(stubBehaviour = PlaceOrderStubBehaviour.NeverResponds) { stub ->
            // Per-call override forces the deadline to fire at 200ms (+ grace).
            val result = runBlocking {
                GrpcFixGatewayClient(stub.channel).placeOrder(
                    clOrdId = "ord-to", venue = "NYSE", instrumentId = "AAPL",
                    side = Side.BUY, orderType = "MARKET", quantity = BigDecimal("1"),
                    limitPrice = null, timeInForce = "DAY",
                    venueAckTimeoutMs = 200,
                )
            }
            result.status shouldBe PlaceOrderStatus.DEADLINE_EXCEEDED
            result.rejectReason shouldBe "grpc:DEADLINE_EXCEEDED"
        }
    }

    test("venue_ack_timeout_ms is forwarded to fix-gateway on the wire") {
        runPlaceOrderFakeServer(stubBehaviour = PlaceOrderStubBehaviour.PendingNew("V1")) { stub ->
            runBlocking {
                GrpcFixGatewayClient(stub.channel).placeOrder(
                    clOrdId = "ord-tm", venue = "NYSE", instrumentId = "AAPL",
                    side = Side.BUY, orderType = "MARKET", quantity = BigDecimal("1"),
                    limitPrice = null, timeInForce = "DAY",
                    venueAckTimeoutMs = 800,
                )
            }
            stub.received[0].venueAckTimeoutMs shouldBe 800
        }
    }
})

private sealed class PlaceOrderStubBehaviour {
    data class PendingNew(val venueOrderId: String) : PlaceOrderStubBehaviour()
    data class Rejected(val reason: String) : PlaceOrderStubBehaviour()
    object SessionDown : PlaceOrderStubBehaviour()
    object UnknownVenue : PlaceOrderStubBehaviour()
    object InvalidRequest : PlaceOrderStubBehaviour()
    object DuplicateInFlight : PlaceOrderStubBehaviour()
    object NeverResponds : PlaceOrderStubBehaviour()
}

private class RecordingFakePlaceOrder(private val behaviour: PlaceOrderStubBehaviour) :
    FixGatewayGrpc.FixGatewayImplBase() {

    val received = mutableListOf<PlaceOrderRequest>()

    override fun placeOrder(
        request: PlaceOrderRequest,
        responseObserver: StreamObserver<PlaceOrderResponse>,
    ) {
        received += request
        when (behaviour) {
            is PlaceOrderStubBehaviour.PendingNew -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setVenueOrderId(behaviour.venueOrderId)
                    .setStatus(PlaceOrderResponse.Status.PENDING_NEW).build()
            )
            is PlaceOrderStubBehaviour.Rejected -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(PlaceOrderResponse.Status.REJECTED)
                    .setRejectReason(behaviour.reason).build()
            )
            PlaceOrderStubBehaviour.SessionDown -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(PlaceOrderResponse.Status.SESSION_DOWN).build()
            )
            PlaceOrderStubBehaviour.UnknownVenue -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(PlaceOrderResponse.Status.UNKNOWN_VENUE).build()
            )
            PlaceOrderStubBehaviour.InvalidRequest -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(PlaceOrderResponse.Status.INVALID_REQUEST).build()
            )
            PlaceOrderStubBehaviour.DuplicateInFlight -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(PlaceOrderResponse.Status.DUPLICATE_IN_FLIGHT).build()
            )
            PlaceOrderStubBehaviour.NeverResponds -> {
                // Hold the call; the client deadline must fire.
            }
        }
    }
}

private fun StreamObserver<PlaceOrderResponse>.respond(response: PlaceOrderResponse) {
    onNext(response)
    onCompleted()
}

private class PlaceOrderStubHandle(
    val server: Server,
    val received: MutableList<PlaceOrderRequest>,
) {
    val channel = ManagedChannelBuilder
        .forAddress("localhost", server.port)
        .usePlaintext()
        .build()

    fun close() {
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
    }
}

private fun runPlaceOrderFakeServer(stubBehaviour: PlaceOrderStubBehaviour, block: (PlaceOrderStubHandle) -> Unit) {
    val service = RecordingFakePlaceOrder(stubBehaviour)
    val server = NettyServerBuilder.forPort(0).addService(service).build().start()
    val handle = PlaceOrderStubHandle(server, service.received)
    try {
        block(handle)
    } finally {
        handle.close()
    }
}
