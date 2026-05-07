package com.kinetix.position.fix

import com.kinetix.proto.execution.FixGatewayGrpc
import com.kinetix.proto.execution.IsVenueOpenRequest
import com.kinetix.proto.execution.IsVenueOpenResponse
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GrpcVenueOpenCheckerAcceptanceTest : FunSpec({

    test("returns true when fix-gateway responds open=true") {
        withFakeServer(reply = IsVenueOpenResponse.newBuilder().setOpen(true).build()) { channel ->
            val checker = GrpcVenueOpenChecker(channel)
            checker.isOpen("NYSE", Instant.parse("2026-05-04T18:00:00Z")) shouldBe true
        }
    }

    test("returns false when fix-gateway responds open=false") {
        withFakeServer(reply = IsVenueOpenResponse.newBuilder().setOpen(false).build()) { channel ->
            GrpcVenueOpenChecker(channel).isOpen("NYSE", Instant.now()) shouldBe false
        }
    }

    test("falls back to open=true and notifies the failure callback when the RPC fails") {
        val capturedVenue = AtomicReference<String>()
        val capturedError = AtomicReference<Throwable>()
        // Aggressive deadline; the stub never responds so DEADLINE_EXCEEDED fires.
        withFakeServer(reply = null) { channel ->
            val checker = GrpcVenueOpenChecker(
                channel = channel,
                onRpcFailure = { v, t -> capturedVenue.set(v); capturedError.set(t) },
                rpcDeadlineMs = 200,
                fallbackOnFailure = true,
            )
            checker.isOpen("NYSE", Instant.now()) shouldBe true
        }
        capturedVenue.get() shouldBe "NYSE"
        (capturedError.get() != null) shouldBe true
    }
})

private class FakeFixGateway(
    private val reply: IsVenueOpenResponse?,
) : FixGatewayGrpc.FixGatewayImplBase() {
    override fun isVenueOpen(
        request: IsVenueOpenRequest,
        responseObserver: StreamObserver<IsVenueOpenResponse>,
    ) {
        if (reply != null) {
            responseObserver.onNext(reply)
            responseObserver.onCompleted()
        }
        // Else hold; client deadline fires.
    }
}

private fun withFakeServer(reply: IsVenueOpenResponse?, block: (io.grpc.ManagedChannel) -> Unit) {
    val service = FakeFixGateway(reply)
    val server: Server = NettyServerBuilder.forPort(0).addService(service).build().start()
    val channel = ManagedChannelBuilder
        .forAddress("localhost", server.port)
        .usePlaintext()
        .build()
    try {
        block(channel)
    } finally {
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
    }
}
