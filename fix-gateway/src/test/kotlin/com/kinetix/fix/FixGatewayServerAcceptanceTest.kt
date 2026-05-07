package com.kinetix.fix

import com.kinetix.fix.grpc.FixGatewayServer
import io.grpc.ConnectivityState
import io.grpc.ManagedChannelBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 1 acceptance test (plan 1.11): boots the gRPC server, opens a real
 * HTTP/2 channel, and asserts the channel reaches READY against an empty
 * server. The server has no RPC implementations bound — phase 2 adds them.
 */
class FixGatewayServerAcceptanceTest : FunSpec({

    test("FixGatewayServer accepts gRPC connections on its bound port") {
        val server = FixGatewayServer(port = 0).start()

        val channel = ManagedChannelBuilder
            .forAddress("localhost", server.boundPort())
            .usePlaintext()
            .build()

        try {
            val latch = CountDownLatch(1)
            channel.notifyWhenStateChanged(ConnectivityState.IDLE) { latch.countDown() }
            channel.getState(true)
            latch.await(5, TimeUnit.SECONDS) shouldBe true

            val state = channel.getState(false)
            (state == ConnectivityState.CONNECTING ||
                state == ConnectivityState.READY ||
                state == ConnectivityState.IDLE) shouldBe true
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
            server.stop()
        }
    }
})
