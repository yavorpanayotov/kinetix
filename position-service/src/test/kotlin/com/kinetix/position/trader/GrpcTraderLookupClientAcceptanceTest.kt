package com.kinetix.position.trader

import com.kinetix.common.model.TraderId
import com.kinetix.proto.referencedata.GetTraderRequest
import com.kinetix.proto.referencedata.GetTraderResponse
import com.kinetix.proto.referencedata.TraderLookupServiceGrpc
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit

private class FakeTraderLookup(
    private val knownIds: Set<String>,
    private val statusOnUnknown: Status = Status.NOT_FOUND,
    private val statusOnAll: Status? = null,
) : TraderLookupServiceGrpc.TraderLookupServiceImplBase() {
    override fun getTrader(
        request: GetTraderRequest,
        responseObserver: StreamObserver<GetTraderResponse>,
    ) {
        if (statusOnAll != null) {
            responseObserver.onError(statusOnAll.asRuntimeException())
            return
        }
        if (request.traderId in knownIds) {
            responseObserver.onNext(
                GetTraderResponse.newBuilder()
                    .setId(request.traderId)
                    .setName("Trader ${request.traderId}")
                    .setDeskId("desk-x")
                    .build(),
            )
            responseObserver.onCompleted()
        } else {
            responseObserver.onError(statusOnUnknown.asRuntimeException())
        }
    }
}

private fun withFakeServer(service: FakeTraderLookup, block: (io.grpc.ManagedChannel) -> Unit) {
    val server: Server = NettyServerBuilder.forPort(0).addService(service).build().start()
    val channel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
    try {
        block(channel)
    } finally {
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
    }
}

class GrpcTraderLookupClientAcceptanceTest : FunSpec({

    test("exists returns true when the server returns the trader") {
        withFakeServer(FakeTraderLookup(knownIds = setOf("tr-eg-001"))) { channel ->
            GrpcTraderLookupClient(channel).exists(TraderId("tr-eg-001")) shouldBe true
        }
    }

    test("exists returns false when the server returns NOT_FOUND") {
        withFakeServer(FakeTraderLookup(knownIds = emptySet())) { channel ->
            GrpcTraderLookupClient(channel).exists(TraderId("ghost")) shouldBe false
        }
    }

    test("non-NOT_FOUND statuses bubble up as TraderLookupRpcException") {
        withFakeServer(
            FakeTraderLookup(knownIds = emptySet(), statusOnAll = Status.UNAVAILABLE),
        ) { channel ->
            shouldThrow<TraderLookupRpcException> {
                GrpcTraderLookupClient(channel).exists(TraderId("tr-eg-001"))
            }
        }
    }
})
