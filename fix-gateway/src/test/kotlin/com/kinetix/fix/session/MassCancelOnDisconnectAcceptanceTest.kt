package com.kinetix.fix.session

import com.kinetix.fix.grpc.FixGatewayServer
import com.kinetix.fix.grpc.FixGatewayServiceImpl
import com.kinetix.fix.kafka.RecordingExecutionReportPublisher
import com.kinetix.fix.persistence.DatabaseConfig
import com.kinetix.fix.persistence.DatabaseFactory
import com.kinetix.fix.session.FixMessageLogEntry
import com.kinetix.fix.session.FixMessageLogRepository
import com.kinetix.fix.session.SessionReconciliationCoordinator
import com.kinetix.fix.testing.InMemoryFixCounterpartyFixture
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
import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Plan 2.13 — MassCancelOnDisconnectAcceptanceTest.
 *
 * Scenario: an in-memory FIX acceptor drops the session mid-flow with 3 open orders
 * recorded in `fix_message_log`. On reconnect, fix-gateway must:
 *
 *   (a) NOT accept new outbound `PlaceOrderRequest` RPCs until reconciliation completes —
 *       returns `SESSION_DOWN` with `detail = "reconciling"`.
 *   (b) Send `OrderStatusRequest` (35=H) for each open `clOrdID` recorded in
 *       `fix_message_log` for that venue.
 *   (c) Emit `fix_session_reconciliation_total{venue, outcome}` Prometheus counter.
 *
 * Infrastructure: Testcontainers Postgres (for JdbcStoreFactory + Flyway-managed schema).
 * No Kafka required for this test — the reconciliation flow is observed at the gRPC
 * and FIX-message layers only.
 */
class MassCancelOnDisconnectAcceptanceTest : FunSpec({

    val postgres = PostgreSQLContainer(
        DockerImageName.parse("timescale/timescaledb:latest-pg17")
            .asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("fix_gateway_reconnect_test")
        .withUsername("test")
        .withPassword("test")

    beforeSpec { postgres.start() }
    afterSpec { postgres.stop() }

    val openClOrdIds = listOf("open-ord-1", "open-ord-2", "open-ord-3")

    test("on reconnect: blocks PlaceOrder with SESSION_DOWN reconciling, sends 35=H for each open order, emits reconciliation counter") {
        val db = DatabaseFactory.init(
            DatabaseConfig(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
            )
        )

        // Clean state
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE fix_message_log CASCADE")
        }

        val messageLogRepository = ExposedFixMessageLogRepository(db)

        // Seed 3 open outbound 35=D rows for venue NYSE.
        for (clOrdId in openClOrdIds) {
            messageLogRepository.insert(
                FixMessageLogEntry(
                    venue = "NYSE",
                    direction = "OUT",
                    msgType = "D",
                    rawMessage = "35=D|11=$clOrdId",
                    clOrdId = clOrdId,
                    venueOrderId = null,
                    orderStatus = "OPEN",
                    sentAt = Instant.now().minusSeconds(60),
                )
            )
        }

        val meterRegistry = SimpleMeterRegistry()

        // Reconciliation coordinator: no real SessionID lookup needed for assertion (b) —
        // we assert at the acceptor's messagesReceivedByAcceptor list instead.
        // The coordinator is wired with a venueSessionLookup that routes to the live session.
        var coordinator: SessionReconciliationCoordinator? = null

        coordinator = SessionReconciliationCoordinator(
            messageLogRepository = messageLogRepository,
            meterRegistry = meterRegistry,
            reconcileTimeoutMs = 3_000L,
            venueSessionLookup = { venue ->
                // Session lookup happens AFTER logon when the session is registered.
                // The real production lookup queries QuickFIX/J's session registry by
                // SessionID. Here we defer to the fixture's acceptor's known session.
                quickfix.Session.lookupSession(
                    quickfix.SessionID("FIX.4.4", "KINETIX", "NYSE")
                )?.sessionID
            },
        )

        val handler = InboundFixHandler(
            converter = FIXMessageConverter(),
            publisher = RecordingExecutionReportPublisher(),
            meterRegistry = meterRegistry,
        )

        val sessionSender = RecordingFixSessionSender(initialOutcome = SendOutcome.Sent)

        val fixGatewayService = FixGatewayServiceImpl(
            venueSessionRegistry = VenueSessionRegistry(),
            venueCutoffRegistry = VenueCutoffRegistry(),
            cancelMessageBuilder = CancelMessageBuilder(),
            newOrderSingleBuilder = NewOrderSingleBuilder(),
            pendingNewCorrelator = PendingNewCorrelator(meterRegistry = meterRegistry),
            sessionSender = sessionSender,
            originalOrderLookup = { _, _ -> null },
            reconciliationCoordinator = coordinator,
        )

        val grpcServer = FixGatewayServer(port = 0, services = listOf(fixGatewayService)).start()
        val channel = ManagedChannelBuilder
            .forAddress("localhost", grpcServer.boundPort())
            .usePlaintext()
            .build()
        val stub = FixGatewayGrpc.newBlockingStub(channel)

        try {
            val fixture = InMemoryFixCounterpartyFixture(
                jdbcUrl = postgres.jdbcUrl,
                jdbcUser = postgres.username,
                jdbcPassword = postgres.password,
                inboundFixHandler = handler,
                reconciliationCoordinator = coordinator,
                resetOnLogon = true,
            )

            fixture.use {
                // Phase 1: Start and wait for initial logon.
                fixture.start()
                fixture.awaitLogon(timeoutMs = 10_000) shouldBe true

                // Let reconciliation proceed (the coordinator transitions through RECONCILING
                // then ACTIVE after the timeout since no 35=H responses are returned by the
                // in-memory acceptor — we are observing, not simulating venue replies).
                delay(3_500)

                // Confirm initial state is ACTIVE.
                coordinator.currentState("NYSE") shouldBe SessionReconciliationCoordinator.State.ACTIVE

                // Phase 2: Disconnect the session (venue drops connection mid-flow).
                fixture.resetLogonLatch()
                fixture.disconnect()

                // Give the coordinator time to transition to DOWN.
                delay(500)
                coordinator.currentState("NYSE") shouldBe SessionReconciliationCoordinator.State.DOWN

                // Phase 3: Assert (a) — PlaceOrder RPCs return SESSION_DOWN with detail "reconciling"
                // during the reconnect + reconciliation window.
                // We reconnect first (the fixture's reconnect logic fires automatically),
                // then verify the coordinator enters RECONCILING before ACTIVE.
                fixture.awaitLogon(timeoutMs = 10_000) shouldBe true

                // Immediately after logon the coordinator is RECONCILING (before the timeout elapses).
                val stateAfterReconnect = coordinator.currentState("NYSE")
                stateAfterReconnect shouldBe SessionReconciliationCoordinator.State.RECONCILING

                // Assertion (a): PlaceOrder returns SESSION_DOWN with detail="reconciling".
                val response = stub.placeOrder(
                    PlaceOrderRequest.newBuilder()
                        .setClOrdId("ord-during-reconciliation")
                        .setVenue("NYSE")
                        .setInstrumentId("AAPL")
                        .setSide(Side.BUY)
                        .setOrderType(OrderType.LIMIT)
                        .setQuantity("100")
                        .setLimitPrice("150.00")
                        .setTimeInForce(TimeInForce.TIF_DAY)
                        .setVenueAckTimeoutMs(500)
                        .build()
                )
                response.status shouldBe PlaceOrderResponse.Status.SESSION_DOWN
                response.rejectReason shouldContain "reconciling"

                // Assertion (b): The acceptor received 35=H messages for each open clOrdID.
                // Give the coordinator time to send the 35=H messages.
                delay(500)
                val statusRequests = fixture.messagesReceivedByAcceptor
                    .filter { it.header.getString(35) == "H" }
                val receivedClOrdIds = statusRequests.map { it.getString(11) }.toSet()
                receivedClOrdIds shouldBe openClOrdIds.toSet()

                // Assertion (c): Wait for reconciliation to complete and check the counter.
                delay(3_500)
                val counter = meterRegistry.counter(
                    "fix_session_reconciliation_total",
                    "venue", "NYSE",
                    "outcome", "timeout",
                ).count()
                // At least one reconciliation cycle completed (initial logon + post-disconnect logon).
                (counter >= 1.0) shouldBe true
            }
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
            grpcServer.stop()
        }
    }
})
