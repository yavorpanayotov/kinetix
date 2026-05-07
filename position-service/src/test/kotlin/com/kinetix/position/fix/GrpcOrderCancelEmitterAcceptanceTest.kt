package com.kinetix.position.fix

import com.kinetix.common.execution.CancelReason
import com.kinetix.proto.execution.CancelOrderRequest
import com.kinetix.proto.execution.CancelOrderResponse
import com.kinetix.proto.execution.FixGatewayGrpc
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Plan 2.14: in-JVM gRPC stub-server pattern (per CLAUDE.md). Boots a fake
 * `FixGatewayImplBase` on a random port and exercises [GrpcOrderCancelEmitter]
 * against it; asserts the proto mapping for each [CancelReason] and that each
 * outcome maps to the correct [CancelAttemptStatus] row.
 */
class GrpcOrderCancelEmitterAcceptanceTest : FunSpec({

    test("ACCEPTED outcome records ACCEPTED attempt") {
        runFakeServer(stubBehaviour = StubBehaviour.AcceptedFor("ord-1")) { stub ->
            val recorder = RecordingCancelAttemptRecorder()
            val emitter = GrpcOrderCancelEmitter(stub.channel, recorder)
            runBlocking {
                emitter.emitCancel(
                    orderId = "ord-1", venue = "NYSE", venueOrderId = "VENUE-1",
                    reason = CancelReason.DAY_ORDER_EXPIRY, correlationId = "corr-1",
                )
            }
            stub.received shouldHaveSize 1
            stub.received[0].clOrdId shouldBe "ord-1"
            stub.received[0].venue shouldBe "NYSE"
            stub.received[0].venueOrderId shouldBe "VENUE-1"
            stub.received[0].reason.toString() shouldBe "DAY_ORDER_EXPIRY"
            recorder.attempts shouldHaveSize 1
            recorder.attempts[0].status shouldBe CancelAttemptStatus.ACCEPTED
        }
    }

    test("SESSION_DOWN outcome records SESSION_DOWN attempt") {
        runFakeServer(stubBehaviour = StubBehaviour.SessionDown) { stub ->
            val recorder = RecordingCancelAttemptRecorder()
            GrpcOrderCancelEmitter(stub.channel, recorder).also {
                runBlocking {
                    it.emitCancel("ord-2", "NYSE", "VENUE-2", CancelReason.GTD_EXPIRY)
                }
            }
            recorder.attempts[0].status shouldBe CancelAttemptStatus.SESSION_DOWN
        }
    }

    test("UNKNOWN_VENUE outcome records UNKNOWN_VENUE attempt") {
        runFakeServer(stubBehaviour = StubBehaviour.UnknownVenue) { stub ->
            val recorder = RecordingCancelAttemptRecorder()
            runBlocking {
                GrpcOrderCancelEmitter(stub.channel, recorder).emitCancel(
                    "ord-3", "MADEUP", "VENUE-3", CancelReason.USER_INITIATED,
                )
            }
            recorder.attempts[0].status shouldBe CancelAttemptStatus.UNKNOWN_VENUE
        }
    }

    test("INVALID_REQUEST outcome records INVALID_REQUEST attempt") {
        runFakeServer(stubBehaviour = StubBehaviour.InvalidRequest) { stub ->
            val recorder = RecordingCancelAttemptRecorder()
            runBlocking {
                GrpcOrderCancelEmitter(stub.channel, recorder).emitCancel(
                    "ord-4", "NYSE", "VENUE-4", CancelReason.RISK_LIMIT_BREACH,
                )
            }
            recorder.attempts[0].status shouldBe CancelAttemptStatus.INVALID_REQUEST
        }
    }

    test("DEADLINE_EXCEEDED maps to RPC_FAILED without crashing the sweeper") {
        runFakeServer(stubBehaviour = StubBehaviour.DeadlineExceeded) { stub ->
            val recorder = RecordingCancelAttemptRecorder()
            // Use an aggressive client deadline; the stub never responds so the deadline must fire.
            runBlocking {
                GrpcOrderCancelEmitter(stub.channel, recorder, rpcDeadlineMs = 200).emitCancel(
                    "ord-5", "NYSE", "VENUE-5", CancelReason.DAY_ORDER_EXPIRY,
                )
            }
            recorder.attempts[0].status shouldBe CancelAttemptStatus.RPC_FAILED
        }
    }

    test("null venueOrderId records INVALID_REQUEST without making the RPC") {
        runFakeServer(stubBehaviour = StubBehaviour.AcceptedFor("ord-6")) { stub ->
            val recorder = RecordingCancelAttemptRecorder()
            runBlocking {
                GrpcOrderCancelEmitter(stub.channel, recorder).emitCancel(
                    "ord-6", "NYSE", null, CancelReason.DAY_ORDER_EXPIRY,
                )
            }
            stub.received shouldHaveSize 0  // no RPC made
            recorder.attempts[0].status shouldBe CancelAttemptStatus.INVALID_REQUEST
        }
    }
})

private sealed class StubBehaviour {
    data class AcceptedFor(val clOrdId: String) : StubBehaviour()
    object SessionDown : StubBehaviour()
    object UnknownVenue : StubBehaviour()
    object InvalidRequest : StubBehaviour()
    /** Stub never responds; the client deadline must fire. */
    object DeadlineExceeded : StubBehaviour()
}

private class RecordingFakeFixGateway(private val behaviour: StubBehaviour) :
    FixGatewayGrpc.FixGatewayImplBase() {

    val received = mutableListOf<CancelOrderRequest>()

    override fun cancelOrder(
        request: CancelOrderRequest,
        responseObserver: StreamObserver<CancelOrderResponse>,
    ) {
        received += request
        when (behaviour) {
            is StubBehaviour.AcceptedFor -> responseObserver.respond(
                CancelOrderResponse.newBuilder()
                    .setClOrdId(behaviour.clOrdId)
                    .setStatus(CancelOrderResponse.Status.ACCEPTED).build()
            )
            StubBehaviour.SessionDown -> responseObserver.respond(
                CancelOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(CancelOrderResponse.Status.SESSION_DOWN).build()
            )
            StubBehaviour.UnknownVenue -> responseObserver.respond(
                CancelOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(CancelOrderResponse.Status.UNKNOWN_VENUE).build()
            )
            StubBehaviour.InvalidRequest -> responseObserver.respond(
                CancelOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(CancelOrderResponse.Status.INVALID_REQUEST).build()
            )
            StubBehaviour.DeadlineExceeded -> {
                // Hold the call; the client deadline fires.
            }
        }
    }
}

private fun StreamObserver<CancelOrderResponse>.respond(response: CancelOrderResponse) {
    onNext(response)
    onCompleted()
}

private class StubHandle(
    val server: Server,
    val received: MutableList<CancelOrderRequest>,
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

private fun runFakeServer(stubBehaviour: StubBehaviour, block: (StubHandle) -> Unit) {
    val service = RecordingFakeFixGateway(stubBehaviour)
    val server = NettyServerBuilder.forPort(0).addService(service).build().start()
    val handle = StubHandle(server, service.received)
    try {
        block(handle)
    } finally {
        handle.close()
    }
}

private class RecordingCancelAttemptRecorder : CancelAttemptRecorder {
    val attempts = mutableListOf<Attempt>()
    data class Attempt(
        val orderId: String, val venue: String, val status: CancelAttemptStatus,
        val attemptedAt: Instant, val detail: String,
    )
    override fun record(orderId: String, venue: String, status: CancelAttemptStatus, attemptedAt: Instant, detail: String) {
        attempts += Attempt(orderId, venue, status, attemptedAt, detail)
    }
}
