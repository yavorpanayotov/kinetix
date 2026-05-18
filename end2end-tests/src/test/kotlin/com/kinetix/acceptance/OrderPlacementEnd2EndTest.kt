package com.kinetix.acceptance

import com.kinetix.fix.grpc.FixGatewayServer
import com.kinetix.fix.grpc.FixGatewayServiceImpl
import com.kinetix.fix.session.CancelMessageBuilder
import com.kinetix.fix.session.FixSessionSender
import com.kinetix.fix.session.NewOrderSingleBuilder
import com.kinetix.fix.session.PendingNewCorrelator
import com.kinetix.fix.session.SendOutcome
import com.kinetix.fix.venue.VenueCutoffRegistry
import com.kinetix.fix.venue.VenueSessionRegistry
import com.kinetix.position.fix.ExposedExecutionOrderRepository
import com.kinetix.position.fix.FIXOrderSender
import com.kinetix.position.fix.FIXSession
import com.kinetix.position.fix.FIXSessionRepository
import com.kinetix.position.fix.FIXSessionStatus
import com.kinetix.position.fix.GhostFill
import com.kinetix.position.fix.GhostFillRepository
import com.kinetix.position.fix.GrpcFixGatewayClient
import com.kinetix.position.fix.Order
import com.kinetix.position.fix.OrderStatus
import com.kinetix.position.fix.OrderSubmissionService
import com.kinetix.position.model.LimitBreachResult
import com.kinetix.position.persistence.DatabaseConfig
import com.kinetix.position.persistence.DatabaseFactory
import com.kinetix.position.routes.dtos.OrderResponse
import com.kinetix.position.routes.orderRoutes
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.PreTradeCheckService
import com.kinetix.testsupport.containers.TestcontainerCaps
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.testcontainers.containers.PostgreSQLContainer
import quickfix.Message
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end order placement (ADR-0035 / fix-gateway plan §4.11).
 *
 * Wires the full hot path:
 *
 *   POST /api/v1/orders            position-service                        fix-gateway
 *   ─────────────────────►  OrderSubmissionService ──gRPC──► FixGatewayServiceImpl
 *                                       │                            │
 *                                       │                            ▼ register-deferred + send
 *                                       │                  PendingNewCorrelator + InMemoryVenueAcceptor
 *                                       │                            │ (simulated 35=8 OrdStatus=A)
 *                                       │                            ▼
 *                                       └◄────PENDING_NEW + venueOrderId──┘
 *
 * Asserts the contract that the trader sees in the UI: the REST response on
 * `Created` carries `status=SENT` AND a populated `venueOrderId` from the
 * venue's tag 37, and the persisted DB row reflects both. This is the
 * cross-service contract that `OrderSubmissionServiceAcceptanceTest` (gRPC
 * stub) and `PlaceOrderRpcAcceptanceTest` (correlator-driven) cover in
 * isolation; this test proves they compose.
 *
 * The "in-memory acceptor" is a [FixSessionSender] that, on receipt of a
 * 35=D NewOrderSingle, asynchronously wakes the [PendingNewCorrelator] with
 * a synthetic `PendingNew` outcome — the same shape the production
 * `InboundFixHandler` would deliver after parsing a real venue 35=8 ack.
 * The wire-format byte shape of 35=D is pinned separately by
 * `PlaceOrderRpcAcceptanceTest`; this test's job is the cross-service flow.
 */
class OrderPlacementEnd2EndTest : FunSpec({

    val postgres = TestcontainerCaps.tunePostgres(
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("position_e2e_place")
            .withUsername("test")
            .withPassword("test"),
    )

    lateinit var orderRepo: ExposedExecutionOrderRepository
    lateinit var orderSubmissionService: OrderSubmissionService
    lateinit var fixGatewayServer: FixGatewayServer
    lateinit var fixGatewayChannel: ManagedChannel
    lateinit var venueScope: CoroutineScope
    lateinit var lastSeenVenueOrderId: AtomicReference<String?>

    beforeSpec {
        postgres.start()

        val db = DatabaseFactory.init(
            DatabaseConfig(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
                maxPoolSize = 5,
            )
        )
        orderRepo = ExposedExecutionOrderRepository(db)

        // --- fix-gateway side ----------------------------------------------
        val correlator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        venueScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        lastSeenVenueOrderId = AtomicReference()

        // In-memory venue acceptor: on 35=D send, schedule a PENDING_NEW ack
        // a few millis later — simulates a co-lo venue's near-instant 35=8
        // OrdStatus=A. Long enough to exercise the register-then-await flow,
        // short enough to never approach NYSE's 200ms default deadline.
        val venueAcceptor = InMemoryVenueAcceptor(
            correlator = correlator,
            scope = venueScope,
            ackDelayMillis = 5,
            assignVenueOrderId = { clOrdId -> "VEN-${clOrdId.takeLast(8)}".also(lastSeenVenueOrderId::set) },
        )

        val fixGatewayService = FixGatewayServiceImpl(
            venueSessionRegistry = VenueSessionRegistry(),
            venueCutoffRegistry = VenueCutoffRegistry(),
            cancelMessageBuilder = CancelMessageBuilder(),
            newOrderSingleBuilder = NewOrderSingleBuilder(),
            pendingNewCorrelator = correlator,
            sessionSender = venueAcceptor,
            originalOrderLookup = { _, _ -> null },
            clock = { Instant.parse("2026-05-04T18:00:00Z") }, // 14:00 ET, NYSE open
        )
        fixGatewayServer = FixGatewayServer(
            port = 0,
            services = listOf(fixGatewayService as io.grpc.BindableService),
        ).start()

        fixGatewayChannel = ManagedChannelBuilder
            .forAddress("localhost", fixGatewayServer.boundPort())
            .usePlaintext()
            .build()

        // --- position-service side -----------------------------------------
        val sessionRepo = object : FIXSessionRepository {
            override suspend fun save(session: FIXSession) = Unit
            override suspend fun findById(sessionId: String): FIXSession? = null
            override suspend fun findAll(): List<FIXSession> = emptyList()
            override suspend fun updateStatus(sessionId: String, status: FIXSessionStatus) = Unit
        }
        val noopSender = object : FIXOrderSender {
            override suspend fun send(order: Order, session: FIXSession) = Unit
        }
        val approvingPreTradeCheck = object : PreTradeCheckService {
            override suspend fun check(command: BookTradeCommand) = LimitBreachResult(emptyList())
        }
        orderSubmissionService = OrderSubmissionService(
            orderRepository = orderRepo,
            sessionRepository = sessionRepo,
            fixOrderSender = noopSender,
            preTradeCheckService = approvingPreTradeCheck,
            fixGatewayClient = GrpcFixGatewayClient(fixGatewayChannel),
        )
    }

    afterSpec {
        fixGatewayChannel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
        fixGatewayServer.stop()
        venueScope.cancel()
        postgres.stop()
    }

    test("POST /api/v1/orders → fix-gateway → in-memory venue ack: response has status=SENT and venueOrderId, DB matches") {
        testApplication {
            application {
                installPlacementRoutes(orderSubmissionService)
            }

            val response = client.post("/api/v1/orders") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "bookId": "book-e2e-place",
                      "instrumentId": "AAPL",
                      "side": "BUY",
                      "quantity": "100",
                      "orderType": "LIMIT",
                      "limitPrice": "150.00",
                      "arrivalPrice": "149.90",
                      "instrumentType": "CASH_EQUITY"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.Created

            val body = Json.decodeFromString<OrderResponse>(response.bodyAsText())
            body.status shouldBe OrderStatus.SENT.name
            body.venueOrderId shouldNotBe null
            body.venueOrderId!!.startsWith("VEN-") shouldBe true
            withClue("REST response venueOrderId must equal what the in-memory venue assigned") {
                body.venueOrderId shouldBe lastSeenVenueOrderId.get()
            }

            val persisted = orderRepo.findById(body.orderId)!!
            persisted.status shouldBe OrderStatus.SENT
            persisted.venueOrderId shouldBe body.venueOrderId
        }
    }

    test("REST response surfaces fix-gateway PENDING_FAILED when the venue never acks (deadline)") {
        // Stand up a parallel fix-gateway whose acceptor swallows sends so the
        // correlator must time out — proves the 503-ish error path round-trips
        // back through the REST layer cleanly.
        val silentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val silentCorrelator = PendingNewCorrelator(meterRegistry = SimpleMeterRegistry())
        val silentAcceptor = InMemoryVenueAcceptor(
            correlator = silentCorrelator,
            scope = silentScope,
            ackDelayMillis = -1, // never schedules an ack
            assignVenueOrderId = { _ -> "" },
        )
        val silentService = FixGatewayServiceImpl(
            venueSessionRegistry = VenueSessionRegistry(),
            venueCutoffRegistry = VenueCutoffRegistry(),
            cancelMessageBuilder = CancelMessageBuilder(),
            newOrderSingleBuilder = NewOrderSingleBuilder(),
            pendingNewCorrelator = silentCorrelator,
            sessionSender = silentAcceptor,
            originalOrderLookup = { _, _ -> null },
            clock = { Instant.parse("2026-05-04T18:00:00Z") },
        )
        val silentServer = FixGatewayServer(
            port = 0,
            services = listOf(silentService as io.grpc.BindableService),
        ).start()
        val silentChannel = ManagedChannelBuilder
            .forAddress("localhost", silentServer.boundPort())
            .usePlaintext()
            .build()

        val silentSubmission = OrderSubmissionService(
            orderRepository = orderRepo,
            sessionRepository = object : FIXSessionRepository {
                override suspend fun save(session: FIXSession) = Unit
                override suspend fun findById(sessionId: String): FIXSession? = null
                override suspend fun findAll(): List<FIXSession> = emptyList()
                override suspend fun updateStatus(sessionId: String, status: FIXSessionStatus) = Unit
            },
            fixOrderSender = object : FIXOrderSender {
                override suspend fun send(order: Order, session: FIXSession) = Unit
            },
            preTradeCheckService = object : PreTradeCheckService {
                override suspend fun check(command: BookTradeCommand) = LimitBreachResult(emptyList())
            },
            fixGatewayClient = GrpcFixGatewayClient(silentChannel),
        )

        try {
            testApplication {
                application {
                    installPlacementRoutes(silentSubmission)
                }
                val response = client.post("/api/v1/orders") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "bookId": "book-e2e-fail",
                          "instrumentId": "AAPL",
                          "side": "BUY",
                          "quantity": "100",
                          "orderType": "LIMIT",
                          "limitPrice": "150.00",
                          "arrivalPrice": "149.90",
                          "instrumentType": "CASH_EQUITY"
                        }
                        """.trimIndent(),
                    )
                }

                response.status shouldBe HttpStatusCode.Created

                val body = Json.decodeFromString<OrderResponse>(response.bodyAsText())
                body.status shouldBe OrderStatus.PENDING_FAILED.name
                body.venueOrderId shouldBe null

                val persisted = orderRepo.findById(body.orderId)!!
                persisted.status shouldBe OrderStatus.PENDING_FAILED
                persisted.venueOrderId shouldBe null
            }
        } finally {
            silentChannel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
            silentServer.stop()
            silentScope.cancel()
        }
    }
})

private fun Application.installPlacementRoutes(service: OrderSubmissionService) {
    install(ContentNegotiation) { json() }
    routing { orderRoutes(service, EmptyGhostFillRepository) }
}

private object EmptyGhostFillRepository : GhostFillRepository {
    override suspend fun save(fill: GhostFill) = Unit
    override suspend fun findByOrderId(orderId: String): List<GhostFill> = emptyList()
}

/**
 * Minimal in-process venue counterparty. On receipt of a 35=D NewOrderSingle,
 * pulls tag 11 (ClOrdID) and schedules a `completePendingNew` against the
 * supplied [correlator] — the same wake-up the production
 * [com.kinetix.fix.session.InboundFixHandler] would issue once a venue 35=8
 * OrdStatus=A is parsed. Setting [ackDelayMillis] to a negative value
 * suppresses the ack so callers can drive the timeout path.
 */
private class InMemoryVenueAcceptor(
    private val correlator: PendingNewCorrelator,
    private val scope: CoroutineScope,
    private val ackDelayMillis: Long,
    private val assignVenueOrderId: (clOrdId: String) -> String,
) : FixSessionSender {

    override fun send(venue: String, message: Message): SendOutcome {
        val clOrdId = message.getString(11)
        if (ackDelayMillis >= 0) {
            scope.launch {
                if (ackDelayMillis > 0) delay(ackDelayMillis)
                correlator.completePendingNew(venue, clOrdId, assignVenueOrderId(clOrdId))
            }
        }
        return SendOutcome.Sent
    }
}
