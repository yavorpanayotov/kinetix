package com.kinetix.fix.grpc

import com.google.protobuf.Timestamp
import com.kinetix.fix.session.CancelMessageBuilder
import com.kinetix.fix.session.NoOpFixSessionSender
import com.kinetix.fix.venue.VenueCutoffRegistry
import com.kinetix.fix.venue.VenueSessionRegistry
import com.kinetix.proto.execution.FixGatewayGrpc
import com.kinetix.proto.execution.IsVenueOpenRequest
import io.grpc.ManagedChannelBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Plan 2.13: end-to-end gRPC test for `IsVenueOpen`. Asserts the response for
 * regular session, pre-open, post-cutoff, and unknown-venue cases. The
 * `next_close` timestamp is populated only when the venue is open.
 */
class IsVenueOpenRpcAcceptanceTest : FunSpec({

    val service = FixGatewayServiceImpl(
        venueSessionRegistry = VenueSessionRegistry(),
        venueCutoffRegistry = VenueCutoffRegistry(),
        cancelMessageBuilder = CancelMessageBuilder(),
        sessionSender = NoOpFixSessionSender(),
        originalOrderLookup = { _, _ -> null },
    )

    fun withStub(block: (FixGatewayGrpc.FixGatewayBlockingStub) -> Unit) {
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

    test("open during NYSE session, with populated next_close") {
        withStub { stub ->
            val response = stub.isVenueOpen(
                IsVenueOpenRequest.newBuilder()
                    .setVenue("NYSE")
                    .setAt(toProto(Instant.parse("2026-05-04T18:00:00Z"))) // 14:00 ET
                    .build()
            )
            response.open shouldBe true
            response.nextClose.seconds shouldBe Instant.parse("2026-05-04T20:00:00Z").epochSecond
        }
    }

    test("closed before NYSE open") {
        withStub { stub ->
            val response = stub.isVenueOpen(
                IsVenueOpenRequest.newBuilder()
                    .setVenue("NYSE")
                    .setAt(toProto(Instant.parse("2026-05-04T13:15:00Z"))) // 09:15 ET
                    .build()
            )
            response.open shouldBe false
        }
    }

    test("closed past NYSE cutoff") {
        withStub { stub ->
            val response = stub.isVenueOpen(
                IsVenueOpenRequest.newBuilder()
                    .setVenue("NYSE")
                    .setAt(toProto(Instant.parse("2026-05-04T20:30:00Z"))) // 16:30 ET
                    .build()
            )
            response.open shouldBe false
        }
    }

    test("closed for unknown venue") {
        withStub { stub ->
            val response = stub.isVenueOpen(
                IsVenueOpenRequest.newBuilder()
                    .setVenue("MADEUP")
                    .setAt(toProto(Instant.parse("2026-05-04T18:00:00Z")))
                    .build()
            )
            response.open shouldBe false
        }
    }
})

private fun toProto(instant: Instant): Timestamp = Timestamp.newBuilder()
    .setSeconds(instant.epochSecond)
    .setNanos(instant.nano)
    .build()
