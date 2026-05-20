package com.kinetix.position.fix

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Side
import com.kinetix.position.client.MidPrice
import com.kinetix.position.client.PriceLookupClient
import com.kinetix.position.model.LimitBreachResult
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.PreTradeCheckService
import com.kinetix.proto.execution.FixGatewayGrpc
import com.kinetix.proto.execution.PlaceOrderRequest
import com.kinetix.proto.execution.PlaceOrderResponse
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.concurrent.TimeUnit

/**
 * Plan 4.10: end-to-end acceptance test for [OrderSubmissionService] routing
 * through the real [GrpcFixGatewayClient] against an in-JVM gRPC stub server
 * (per CLAUDE.md), with Testcontainers Postgres backing
 * [ExposedExecutionOrderRepository]. Asserts each [PlaceOrderResponse.Status]
 * surfaces as the right terminal [OrderStatus] AND that the persisted DB row
 * matches — the unit test [OrderSubmissionServiceTest] mocks the repository,
 * so wiring drift only shows up here.
 *
 * Includes the gRPC overhead build-gate from ADR-0035 §4.10 / risk register —
 * `fix_grpc_place_order_overhead_seconds` p95 ≤ 2ms against a no-op stub —
 * measured at the [GrpcFixGatewayClient.placeOrder] boundary so the venue-side
 * synchronous wait does not pollute the budget.
 */
class OrderSubmissionServiceAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val orderRepo = ExposedExecutionOrderRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE execution_fills, execution_cost_analysis, prime_broker_reconciliation, fix_sessions, execution_orders RESTART IDENTITY CASCADE")
        }
    }

    fun newService(
        client: com.kinetix.common.execution.FixGatewayClient,
        priceLookup: PriceLookupClient = FreshMidPriceLookup(),
    ): OrderSubmissionService {
        // The gateway path bypasses the legacy FIX session + sender entirely when
        // [fixGatewayClient] is wired, so these collaborators are never invoked.
        // Empty in-process implementations keep the constructor satisfied.
        val sessionRepo = object : FIXSessionRepository {
            override suspend fun save(session: FIXSession) = Unit
            override suspend fun findById(sessionId: String): FIXSession? = null
            override suspend fun findAll(): List<FIXSession> = emptyList()
            override suspend fun updateStatus(sessionId: String, status: FIXSessionStatus) = Unit
        }
        val sender = object : FIXOrderSender {
            override suspend fun send(order: Order, session: FIXSession) = Unit
        }
        val preTradeCheck = object : PreTradeCheckService {
            override suspend fun check(command: BookTradeCommand) = LimitBreachResult(emptyList())
        }
        return OrderSubmissionService(
            orderRepository = orderRepo,
            sessionRepository = sessionRepo,
            fixOrderSender = sender,
            preTradeCheckService = preTradeCheck,
            priceLookupClient = priceLookup,
            fixGatewayClient = client,
        )
    }

    suspend fun submit(service: OrderSubmissionService, venue: String? = "NYSE"): Order = service.submit(
        bookId = "equity-growth",
        instrumentId = "AAPL",
        side = Side.BUY,
        quantity = BigDecimal("100"),
        orderType = "LIMIT",
        limitPrice = BigDecimal("150.00"),
        fixSessionId = null,
        instrumentType = "CASH_EQUITY",
        venue = venue,
    )

    test("PENDING_NEW response transitions order to SENT and persists venueOrderId in DB") {
        runSubmissionStubServer(SubmissionStub.PendingNew(venueOrderId = "NYSE-OID-42")) { handle ->
            val service = newService(GrpcFixGatewayClient(handle.channel))
            val returned = runBlocking { submit(service) }

            returned.status shouldBe OrderStatus.SENT
            returned.venueOrderId shouldBe "NYSE-OID-42"

            val persisted = runBlocking { orderRepo.findById(returned.orderId) }!!
            persisted.status shouldBe OrderStatus.SENT
            persisted.venueOrderId shouldBe "NYSE-OID-42"
        }
    }

    test("persisted order's arrival price is the server-side mid price, not a caller input") {
        runSubmissionStubServer(SubmissionStub.PendingNew(venueOrderId = "NYSE-OID-1")) { handle ->
            val service = newService(
                GrpcFixGatewayClient(handle.channel),
                priceLookup = FreshMidPriceLookup(amount = "152.37"),
            )
            val returned = runBlocking { submit(service) }

            returned.arrivalPrice.compareTo(BigDecimal("152.37")) shouldBe 0

            val persisted = runBlocking { orderRepo.findById(returned.orderId) }!!
            persisted.arrivalPrice.compareTo(BigDecimal("152.37")) shouldBe 0
        }
    }

    test("a stale server-side mid price rejects the submission before any row is written") {
        runSubmissionStubServer(SubmissionStub.PendingNew(venueOrderId = "NYSE-OID-2")) { handle ->
            val service = newService(
                GrpcFixGatewayClient(handle.channel),
                priceLookup = StaleMidPriceLookup,
            )

            val thrown = runCatching { runBlocking { submit(service) } }.exceptionOrNull()
            (thrown is IllegalArgumentException) shouldBe true
            thrown!!.message!! shouldContain "stale"

            // No order row should have been persisted — validation runs before save.
            val count = runBlocking {
                newSuspendedTransaction(db = db) {
                    exec("SELECT COUNT(*) FROM execution_orders") { rs ->
                        rs.next(); rs.getLong(1)
                    }
                }
            }
            count shouldBe 0L
        }
    }

    test("REJECTED response writes REJECTED row with rejectReason in details") {
        runSubmissionStubServer(SubmissionStub.Rejected("MARKET_HALT")) { handle ->
            val service = newService(GrpcFixGatewayClient(handle.channel))
            val returned = runBlocking { submit(service) }

            returned.status shouldBe OrderStatus.REJECTED
            val persisted = runBlocking { orderRepo.findById(returned.orderId) }!!
            persisted.status shouldBe OrderStatus.REJECTED
            persisted.riskCheckResult shouldBe "REJECTED"
            persisted.riskCheckDetails!! shouldContain "MARKET_HALT"
            persisted.venueOrderId shouldBe null
        }
    }

    test("UNKNOWN_VENUE response writes REJECTED row") {
        runSubmissionStubServer(SubmissionStub.UnknownVenue) { handle ->
            val service = newService(GrpcFixGatewayClient(handle.channel))
            val returned = runBlocking { submit(service, venue = "MADEUP") }

            returned.status shouldBe OrderStatus.REJECTED
            val persisted = runBlocking { orderRepo.findById(returned.orderId) }!!
            persisted.status shouldBe OrderStatus.REJECTED
            persisted.riskCheckResult shouldBe "UNKNOWN_VENUE"
        }
    }

    test("INVALID_REQUEST response writes REJECTED row") {
        runSubmissionStubServer(SubmissionStub.InvalidRequest) { handle ->
            val service = newService(GrpcFixGatewayClient(handle.channel))
            val returned = runBlocking { submit(service) }

            returned.status shouldBe OrderStatus.REJECTED
            val persisted = runBlocking { orderRepo.findById(returned.orderId) }!!
            persisted.status shouldBe OrderStatus.REJECTED
            persisted.riskCheckResult shouldBe "INVALID_REQUEST"
        }
    }

    test("SESSION_DOWN response transitions order to PENDING_FAILED in DB") {
        runSubmissionStubServer(SubmissionStub.SessionDown) { handle ->
            val service = newService(GrpcFixGatewayClient(handle.channel))
            val returned = runBlocking { submit(service) }

            returned.status shouldBe OrderStatus.PENDING_FAILED
            val persisted = runBlocking { orderRepo.findById(returned.orderId) }!!
            persisted.status shouldBe OrderStatus.PENDING_FAILED
            persisted.riskCheckResult shouldBe "SESSION_DOWN"
        }
    }

    test("DUPLICATE_IN_FLIGHT response transitions order to PENDING_FAILED in DB") {
        runSubmissionStubServer(SubmissionStub.DuplicateInFlight) { handle ->
            val service = newService(GrpcFixGatewayClient(handle.channel))
            val returned = runBlocking { submit(service) }

            returned.status shouldBe OrderStatus.PENDING_FAILED
            val persisted = runBlocking { orderRepo.findById(returned.orderId) }!!
            persisted.status shouldBe OrderStatus.PENDING_FAILED
            persisted.riskCheckResult shouldBe "DUPLICATE_IN_FLIGHT"
        }
    }

    test("DEADLINE_EXCEEDED (gRPC StatusRuntimeException) lands the order in PENDING_FAILED") {
        runSubmissionStubServer(SubmissionStub.NeverResponds) { handle ->
            // Aggressive 200ms deadline so the test exits fast; the stub never responds.
            val service = newService(GrpcFixGatewayClient(handle.channel, defaultDeadlineMs = 200L))
            val returned = runBlocking { submit(service) }

            returned.status shouldBe OrderStatus.PENDING_FAILED
            val persisted = runBlocking { orderRepo.findById(returned.orderId) }!!
            persisted.status shouldBe OrderStatus.PENDING_FAILED
            persisted.riskCheckResult shouldBe "DEADLINE_EXCEEDED"
            persisted.riskCheckDetails!! shouldContain "DEADLINE_EXCEEDED"
        }
    }

    test("server-side UNAVAILABLE error (channel close mid-call) lands the order in PENDING_FAILED") {
        runSubmissionStubServer(SubmissionStub.Unavailable) { handle ->
            val service = newService(GrpcFixGatewayClient(handle.channel))
            val returned = runBlocking { submit(service) }

            // Anything other than DEADLINE_EXCEEDED maps to SESSION_DOWN per the
            // adapter (UNAVAILABLE / INTERNAL / channel close all surface here),
            // which the submission service writes through as PENDING_FAILED.
            returned.status shouldBe OrderStatus.PENDING_FAILED
            val persisted = runBlocking { orderRepo.findById(returned.orderId) }!!
            persisted.status shouldBe OrderStatus.PENDING_FAILED
            persisted.riskCheckResult shouldBe "SESSION_DOWN"
        }
    }

    // -----------------------------------------------------------------
    // Build-gate latency: gRPC overhead p95 ≤ 2ms against a no-op stub
    // (ADR-0035 §4.10 / risk register).
    // -----------------------------------------------------------------

    test("gRPC PlaceOrder overhead p95 ≤ 2ms against a no-op stub") {
        runSubmissionStubServer(SubmissionStub.PendingNew("V-bench")) { handle ->
            val client = GrpcFixGatewayClient(handle.channel)

            val warmup = 200
            val timed = 500
            val timings = LongArray(timed)

            runBlocking {
                repeat(warmup) {
                    client.placeOrder(
                        clOrdId = "warm-$it", venue = "NYSE", instrumentId = "AAPL",
                        side = Side.BUY, orderType = "MARKET", quantity = BigDecimal("1"),
                        limitPrice = null, timeInForce = "DAY",
                    )
                }
                repeat(timed) { i ->
                    val start = System.nanoTime()
                    client.placeOrder(
                        clOrdId = "bench-$i", venue = "NYSE", instrumentId = "AAPL",
                        side = Side.BUY, orderType = "MARKET", quantity = BigDecimal("1"),
                        limitPrice = null, timeInForce = "DAY",
                    )
                    timings[i] = System.nanoTime() - start
                }
            }

            timings.sort()
            val p95Ns = timings[(timed * 95) / 100 - 1]
            val p95Ms = p95Ns.toDouble() / 1_000_000.0

            withClue(
                "fix_grpc_place_order_overhead_seconds p95 should be ≤ 2ms (got ${"%.3f".format(p95Ms)}ms; " +
                    "p50=${"%.3f".format(timings[timed / 2].toDouble() / 1_000_000.0)}ms, " +
                    "p99=${"%.3f".format(timings[(timed * 99) / 100 - 1].toDouble() / 1_000_000.0)}ms)"
            ) {
                (p95Ms <= 2.0) shouldBe true
            }
        }
    }
})

// ---------------------------------------------------------------------
// Fake price-service lookups. Arrival price is captured server-side at
// submission time (spec: execution.allium current_mid_price); the caller
// never supplies it, so these stand in for price-service.
// ---------------------------------------------------------------------

private class FreshMidPriceLookup(private val amount: String = "149.90") : PriceLookupClient {
    override suspend fun currentMidPrice(instrumentId: InstrumentId): MidPrice =
        MidPrice(BigDecimal(amount), Currency.getInstance("USD"), Instant.now())
}

private object StaleMidPriceLookup : PriceLookupClient {
    override suspend fun currentMidPrice(instrumentId: InstrumentId): MidPrice =
        MidPrice(BigDecimal("149.90"), Currency.getInstance("USD"), Instant.now().minusSeconds(120))
}

// ---------------------------------------------------------------------
// Stub server plumbing — matches the GrpcOrderCancelEmitterAcceptanceTest
// pattern (CLAUDE.md: fake `FixGatewayImplBase` on
// `NettyServerBuilder.forPort(0)` + plaintext channel).
// ---------------------------------------------------------------------

private sealed class SubmissionStub {
    data class PendingNew(val venueOrderId: String) : SubmissionStub()
    data class Rejected(val reason: String) : SubmissionStub()
    object SessionDown : SubmissionStub()
    object UnknownVenue : SubmissionStub()
    object InvalidRequest : SubmissionStub()
    object DuplicateInFlight : SubmissionStub()
    /** Hold the call open; the client deadline must fire. */
    object NeverResponds : SubmissionStub()
    /** Server-side UNAVAILABLE — surfaces as a `StatusRuntimeException` to the client. */
    object Unavailable : SubmissionStub()
}

private class SubmissionStubServer(private val behaviour: SubmissionStub) :
    FixGatewayGrpc.FixGatewayImplBase() {

    override fun placeOrder(
        request: PlaceOrderRequest,
        responseObserver: StreamObserver<PlaceOrderResponse>,
    ) {
        when (behaviour) {
            is SubmissionStub.PendingNew -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setVenueOrderId(behaviour.venueOrderId)
                    .setStatus(PlaceOrderResponse.Status.PENDING_NEW).build()
            )
            is SubmissionStub.Rejected -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(PlaceOrderResponse.Status.REJECTED)
                    .setRejectReason(behaviour.reason).build()
            )
            SubmissionStub.SessionDown -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(PlaceOrderResponse.Status.SESSION_DOWN).build()
            )
            SubmissionStub.UnknownVenue -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(PlaceOrderResponse.Status.UNKNOWN_VENUE).build()
            )
            SubmissionStub.InvalidRequest -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(PlaceOrderResponse.Status.INVALID_REQUEST).build()
            )
            SubmissionStub.DuplicateInFlight -> responseObserver.respond(
                PlaceOrderResponse.newBuilder()
                    .setClOrdId(request.clOrdId)
                    .setStatus(PlaceOrderResponse.Status.DUPLICATE_IN_FLIGHT).build()
            )
            SubmissionStub.NeverResponds -> {
                // Hold the call open; client deadline fires.
            }
            SubmissionStub.Unavailable -> responseObserver.onError(
                Status.UNAVAILABLE.withDescription("simulated channel close").asRuntimeException()
            )
        }
    }
}

private fun StreamObserver<PlaceOrderResponse>.respond(response: PlaceOrderResponse) {
    onNext(response)
    onCompleted()
}

private class SubmissionStubHandle(val server: Server, val channel: ManagedChannel) {
    fun close() {
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
    }
}

private fun runSubmissionStubServer(behaviour: SubmissionStub, block: (SubmissionStubHandle) -> Unit) {
    val server = NettyServerBuilder.forPort(0)
        .addService(SubmissionStubServer(behaviour))
        .build()
        .start()
    val channel = ManagedChannelBuilder
        .forAddress("localhost", server.port)
        .usePlaintext()
        .build()
    val handle = SubmissionStubHandle(server, channel)
    try {
        block(handle)
    } finally {
        handle.close()
    }
}
