package com.kinetix.observability

/*
 * Verification strategy: InMemorySpanExporter (not Tempo scraping).
 *
 * All services in this test run in-process in the same JVM, sharing a single
 * OpenTelemetrySdk instance backed by an InMemorySpanExporter with
 * SimpleSpanProcessor (synchronous export — no forceFlush needed). This is far
 * faster and more deterministic than standing up a live collector and polling
 * Tempo's HTTP API. The Tempo path only makes sense when services are running
 * in separate processes (e.g. full docker-compose), which is not the case here.
 *
 * Topology (all in-process):
 *   inbound HTTP → position-service (OtelHttpServerPlugin) → KafkaTradeEventPublisher
 *   → Kafka (Testcontainers) → TradeEventConsumer / RetryableConsumer (extracts traceparent)
 *   → VaRCalculationService → GrpcRiskEngineClient (OtelGrpcClientInterceptor)
 *   → in-JVM Netty gRPC server (fake RiskCalculationService, capturing interceptor)
 *
 * All six span boundaries share a single trace_id because:
 *   1. The HTTP request carries a fixed traceparent injected by the test.
 *   2. OtelHttpServerPlugin makes that span current for the position-service handler.
 *   3. KafkaTradeEventPublisher injects the active span context into the record headers.
 *   4. RetryableConsumer extracts the context from those headers (kx-2zlf fix).
 *   5. VaRCalculationService calls the gRPC stub while that context is current.
 *   6. OtelGrpcClientInterceptor injects traceparent into the gRPC metadata.
 */

import com.google.protobuf.Timestamp
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.common.observability.OtelGrpcClientInterceptor
import com.kinetix.common.observability.OtelHttpServerPlugin
import com.kinetix.common.observability.OtelKafkaTracing
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.persistence.DatabaseConfig
import com.kinetix.position.persistence.DatabaseFactory
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.ExposedTransactionalRunner
import com.kinetix.position.service.PositionQueryService
import com.kinetix.position.service.TradeBookingService
import com.kinetix.proto.common.BookId as ProtoBookId
import com.kinetix.proto.risk.ConfidenceLevel as ProtoConfidenceLevel
import com.kinetix.proto.risk.RiskCalculationServiceGrpcKt
import com.kinetix.proto.risk.RiskCalculationType
import com.kinetix.proto.risk.ValuationRequest
import com.kinetix.proto.risk.ValuationResponse
import com.kinetix.risk.cache.InMemoryVaRCache
import com.kinetix.risk.client.GrpcRiskEngineClient
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.kafka.KafkaRiskResultPublisher
import com.kinetix.risk.kafka.TradeEventConsumer
import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.TriggerType
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.service.VaRCalculationService
import com.kinetix.testsupport.containers.TestcontainerCaps
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * In-JVM fake RiskCalculationService that returns a minimal valid [ValuationResponse].
 * Wired to a Netty server so the gRPC stack (interceptors, metadata propagation) is exercised.
 */
private class FakeRiskCalculationService :
    RiskCalculationServiceGrpcKt.RiskCalculationServiceCoroutineImplBase() {

    override suspend fun valuate(request: ValuationRequest): ValuationResponse =
        ValuationResponse.newBuilder()
            .setBookId(ProtoBookId.newBuilder().setValue(request.bookId.value))
            .setCalculationType(RiskCalculationType.PARAMETRIC)
            .setConfidenceLevel(ProtoConfidenceLevel.CL_95)
            .setVarValue(50_000.0)
            .setExpectedShortfall(60_000.0)
            .setCalculatedAt(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond))
            .build()
}

/**
 * Captures inbound gRPC metadata so the test can verify that [traceparent] was
 * propagated from the risk-orchestrator to the (fake) risk-engine.
 */
private class TraceparentCapturingInterceptor : ServerInterceptor {
    val capturedTraceparents: MutableList<String> = CopyOnWriteArrayList()

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val key = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER)
        headers.get(key)?.let { capturedTraceparents.add(it) }
        return next.startCall(call, headers)
    }
}

/**
 * End-to-end trace verification: a single W3C trace_id stitches the full flow
 * from an inbound HTTP call on position-service through Kafka to the risk-engine gRPC.
 */
class EndToEndTraceEnd2EndTest : FunSpec({

    // -------------------------------------------------------------------------
    // Infrastructure
    // -------------------------------------------------------------------------

    val positionDb = TestcontainerCaps.tunePostgres(
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("position_trace_e2e")
            .withUsername("test")
            .withPassword("test"),
    )

    val kafka = TestcontainerCaps.tuneKafka(
        org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1"),
    )

    // -------------------------------------------------------------------------
    // Shared OTel SDK — all services write into the same exporter
    // -------------------------------------------------------------------------

    val spanExporter = InMemorySpanExporter.create()
    val otel: OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build()
        )
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()

    // -------------------------------------------------------------------------
    // Service handles (initialized in beforeSpec)
    // -------------------------------------------------------------------------

    lateinit var bookingService: TradeBookingService
    lateinit var tradeEventConsumer: TradeEventConsumer
    lateinit var traceparentCaptor: TraceparentCapturingInterceptor
    lateinit var consumerScope: CoroutineScope
    var consumerJob: Job? = null

    beforeSpec {
        positionDb.start()
        kafka.start()

        // --- position-service side -------------------------------------------
        val positionDatabase = DatabaseFactory.init(
            DatabaseConfig(
                jdbcUrl = positionDb.jdbcUrl,
                username = positionDb.username,
                password = positionDb.password,
                maxPoolSize = 5,
            )
        )
        val tradeEventRepo = ExposedTradeEventRepository(positionDatabase)
        val positionRepo = ExposedPositionRepository(positionDatabase)
        val transactional = ExposedTransactionalRunner(positionDatabase)

        val producerProps = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
        }
        val kafkaProducer = KafkaProducer<String, String>(producerProps)

        // Publisher carries the OTel instance so it injects traceparent into record headers
        val tradeEventPublisher = KafkaTradeEventPublisher(
            producer = kafkaProducer,
            openTelemetry = otel,
        )
        bookingService = TradeBookingService(tradeEventRepo, positionRepo, transactional, tradeEventPublisher)

        // --- fake risk-engine gRPC server -------------------------------------
        traceparentCaptor = TraceparentCapturingInterceptor()
        val grpcServer = NettyServerBuilder.forPort(0)
            .addService(
                ServerInterceptors.intercept(FakeRiskCalculationService(), traceparentCaptor)
            )
            .build()
            .start()

        val rawChannel = ManagedChannelBuilder
            .forAddress("localhost", grpcServer.port)
            .usePlaintext()
            .build()

        // Wrap with OtelGrpcClientInterceptor — same as production Application.kt
        val tracedChannel = ClientInterceptors.intercept(
            rawChannel,
            OtelGrpcClientInterceptor.newInterceptor(otel),
        )
        val stub = RiskCalculationServiceGrpcKt.RiskCalculationServiceCoroutineStub(tracedChannel)
        val dependenciesStub = com.kinetix.proto.risk.MarketDataDependenciesServiceGrpcKt.MarketDataDependenciesServiceCoroutineStub(tracedChannel)
        val grpcRiskEngineClient = GrpcRiskEngineClient(stub, dependenciesStub)

        // Stub PositionProvider: return one position so VaRCalculationService doesn't short-circuit
        val stubPositionProvider = object : PositionProvider {
            override suspend fun getPositions(bookId: BookId): List<Position> = listOf(
                Position(
                    bookId = bookId,
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    quantity = BigDecimal("100"),
                    averageCost = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                    marketPrice = Money(BigDecimal("155.00"), Currency.getInstance("USD")),
                    instrumentType = InstrumentTypeCode.CASH_EQUITY,
                )
            )
        }

        val resultPublisher = KafkaRiskResultPublisher(kafkaProducer, openTelemetry = otel)

        val varCalculationService = VaRCalculationService(
            positionProvider = stubPositionProvider,
            riskEngineClient = grpcRiskEngineClient,
            resultPublisher = resultPublisher,
        )

        // --- risk-orchestrator Kafka consumer ---------------------------------
        val consumerProps = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "trace-e2e-orchestrator")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        val kafkaConsumer = KafkaConsumer<String, String>(consumerProps)

        // RetryableConsumer carries OTel so it extracts traceparent from record headers
        val retryableConsumer = RetryableConsumer(
            topic = "trades.lifecycle",
            openTelemetry = otel,
        )
        tradeEventConsumer = TradeEventConsumer(
            consumer = kafkaConsumer,
            varCalculationService = varCalculationService,
            retryableConsumer = retryableConsumer,
        )

        consumerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        consumerJob = consumerScope.launch { tradeEventConsumer.start() }
    }

    afterSpec {
        consumerJob?.cancel()
        consumerScope.cancel()
        otel.close()
        positionDb.stop()
        kafka.stop()
    }

    // =========================================================================
    // The test
    // =========================================================================

    test("trade book → Kafka → risk-orchestrator → risk-engine all share one trace_id") {
        // Fixed W3C traceparent — trace_id = 0123456789abcdef0123456789abcdef
        val fixedTraceparent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
        val expectedTraceId = "0123456789abcdef0123456789abcdef"

        // --- POST a trade to position-service with the fixed traceparent ------
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(OtelHttpServerPlugin) { openTelemetry = otel }
                routing {
                    post("/api/v1/positions/{bookId}/trades") {
                        val bookIdParam = call.parameters["bookId"] ?: error("missing bookId")
                        val command = BookTradeCommand(
                            tradeId = com.kinetix.common.model.TradeId("t-trace-e2e-001"),
                            bookId = com.kinetix.common.model.BookId(bookIdParam),
                            instrumentId = com.kinetix.common.model.InstrumentId("AAPL"),
                            assetClass = AssetClass.EQUITY,
                            side = com.kinetix.common.model.Side.BUY,
                            quantity = BigDecimal("100"),
                            price = Money(BigDecimal("155.00"), Currency.getInstance("USD")),
                            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                            instrumentType = "CASH_EQUITY",
                            traderId = com.kinetix.common.model.TraderId("tr-trace-test"),
                        )
                        bookingService.handle(command)
                        call.respondText("""{"status":"booked"}""", ContentType.Application.Json)
                    }
                }
            }

            val response = client.post("/api/v1/positions/book-trace-e2e/trades") {
                contentType(ContentType.Application.Json)
                header("traceparent", fixedTraceparent)
                setBody("{}")
            }
            response.status shouldBe HttpStatusCode.OK
        }

        // --- Wait for all downstream spans to land (up to 10 s) ---------------
        withTimeout(10_000) {
            // Wait until the risk-engine gRPC server received at least one request
            // carrying a traceparent — that proves the full chain fired.
            while (traceparentCaptor.capturedTraceparents.isEmpty()) {
                delay(100)
            }
        }

        // Give SimpleSpanProcessor a moment to flush any trailing spans
        delay(200)

        val allSpans = spanExporter.finishedSpanItems

        // -----------------------------------------------------------------------
        // Span 1: position-service HTTP server span
        // -----------------------------------------------------------------------
        val httpServerSpans = allSpans.filter { it.kind == SpanKind.SERVER }
        httpServerSpans.shouldHaveAtLeastSize(1)
        val positionServerSpan = httpServerSpans.first()
        positionServerSpan.traceId shouldBe expectedTraceId

        // -----------------------------------------------------------------------
        // Span 2/3: Kafka producer span + consumer/handler span
        //
        // KafkaOtelHeaderWriter injects the active context into record headers
        // (no explicit Kafka OTel SDK span is created — trace context travels
        // via W3C header). The consumer side creates a span via RetryableConsumer
        // calling Context.makeCurrent() inside the VaRCalculationService call.
        // We verify the gRPC CLIENT span (from OtelGrpcClientInterceptor) which
        // is created while that extracted context is active and therefore carries
        // the same trace_id. This is the most reliable observable span on the
        // consumer/orchestrator side.
        // -----------------------------------------------------------------------
        val grpcClientSpans = allSpans.filter { it.kind == SpanKind.CLIENT }
        grpcClientSpans.shouldHaveAtLeastSize(1)

        val orchestratorGrpcSpan = grpcClientSpans.first()
        orchestratorGrpcSpan.traceId shouldBe expectedTraceId

        // -----------------------------------------------------------------------
        // Span 4: risk-engine server side — the traceparent captured by the
        // in-JVM gRPC server interceptor carries the same trace_id
        // -----------------------------------------------------------------------
        val capturedTp = traceparentCaptor.capturedTraceparents.first()
        // W3C format: 00-<traceId>-<spanId>-<flags>
        val capturedTraceId = capturedTp.split("-")[1]
        capturedTraceId shouldBe expectedTraceId

        // -----------------------------------------------------------------------
        // All observable spans in our exporter share the same trace_id
        // -----------------------------------------------------------------------
        val traceIds = allSpans.map { it.traceId }.toSet()
        // Every span we recorded must belong to the injected trace
        // (noop spans have traceId "00000000000000000000000000000000" which we exclude)
        val nonNoopTraceIds = traceIds.filter { it != "00000000000000000000000000000000" }
        nonNoopTraceIds.size shouldBe 1
        nonNoopTraceIds.single() shouldBe expectedTraceId
    }
})
