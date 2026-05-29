package com.kinetix.risk.observability

import com.kinetix.common.observability.OtelGrpcClientInterceptor
import com.kinetix.common.observability.OtelHttpClientInterceptor
import com.kinetix.common.observability.OtelHttpServerPlugin
import com.kinetix.common.observability.OtelKafkaTracing
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.ServerServiceDefinition
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.apache.kafka.common.header.internals.RecordHeaders
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Acceptance tests for OTel tracing wiring in risk-orchestrator.
 *
 * Covers the four wiring points exercised by this service:
 * 1. Inbound HTTP → SERVER span created, linked to upstream trace ID.
 * 2. Outbound HTTP client → traceparent injected into upstream call.
 * 3. Outbound gRPC client → traceparent propagated as gRPC metadata.
 * 4. Kafka producer → record headers carry traceparent;
 *    consumer-side extract recovers the original trace ID.
 */
class OtelTracingWiringAcceptanceTest : FunSpec({

    fun buildOtel(exporter: InMemorySpanExporter): OpenTelemetrySdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            )
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()

    test("inbound HTTP request with traceparent header produces a server span linked to the upstream trace") {
        val exporter = InMemorySpanExporter.create()
        val otel = buildOtel(exporter)

        val upstreamTraceparent = "00-00000000000000000000000000000002-0000000000000002-01"

        testApplication {
            application {
                install(OtelHttpServerPlugin) {
                    openTelemetry = otel
                }
                routing {
                    get("/probe") { call.respondText("ok") }
                }
            }
            val response = client.get("/probe") {
                header("traceparent", upstreamTraceparent)
            }
            response.status shouldBe HttpStatusCode.OK
        }

        val spans = exporter.finishedSpanItems
        spans.shouldNotBeEmpty()
        val serverSpan = spans.first { it.kind == SpanKind.SERVER }
        serverSpan.traceId shouldBe "00000000000000000000000000000002"

        otel.close()
    }

    test("outbound HTTP client propagates traceparent to the upstream service") {
        val exporter = InMemorySpanExporter.create()
        val otel = buildOtel(exporter)

        var capturedTraceparent: String? = null
        val mockEngine = MockEngine { request ->
            capturedTraceparent = request.headers["traceparent"]
            respond("ok", HttpStatusCode.OK)
        }

        val tracedClient = HttpClient(mockEngine) {
            install(OtelHttpClientInterceptor) {
                openTelemetry = otel
            }
        }

        val span = otel.getTracer("test").spanBuilder("http-outbound").startSpan()
        val scope = span.makeCurrent()
        try {
            tracedClient.get("http://price-service/api/v1/prices")
        } finally {
            scope.close()
            span.end()
        }

        capturedTraceparent.shouldNotBeNull()
        capturedTraceparent!! shouldStartWith "00-"

        otel.close()
    }

    test("gRPC client interceptor propagates traceparent metadata to server when a span is active") {
        val exporter = InMemorySpanExporter.create()
        val otel = buildOtel(exporter)

        val traceparentKey = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER)
        val captured = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        val methodDescriptor: MethodDescriptor<ByteArray, ByteArray> = MethodDescriptor
            .newBuilder<ByteArray, ByteArray>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("kinetix.risk.RiskService/Ping")
            .setRequestMarshaller(ByteArrayMarshaller)
            .setResponseMarshaller(ByteArrayMarshaller)
            .build()

        val capturingInterceptor = object : ServerInterceptor {
            override fun <Req, Resp> interceptCall(
                call: ServerCall<Req, Resp>,
                headers: Metadata,
                next: ServerCallHandler<Req, Resp>,
            ): ServerCall.Listener<Req> {
                captured.set(headers.get(traceparentKey))
                latch.countDown()
                return next.startCall(call, headers)
            }
        }

        val serviceImpl = ServerCalls.asyncUnaryCall(
            ServerCalls.UnaryMethod<ByteArray, ByteArray> { _, observer ->
                observer.onNext(ByteArray(0))
                observer.onCompleted()
            }
        )
        val serviceDefinition = ServerServiceDefinition.builder("kinetix.risk.RiskService")
            .addMethod(methodDescriptor, serviceImpl)
            .build()

        val server = NettyServerBuilder.forPort(0)
            .addService(ServerInterceptors.intercept(serviceDefinition, capturingInterceptor))
            .build()
            .start()
        val port = server.port

        try {
            val rawChannel = ManagedChannelBuilder
                .forAddress("localhost", port)
                .usePlaintext()
                .build()
            val channel = ClientInterceptors.intercept(
                rawChannel,
                OtelGrpcClientInterceptor.newInterceptor(otel),
            )

            val span = otel.getTracer("test").spanBuilder("grpc-outbound").startSpan()
            val scope = span.makeCurrent()
            try {
                ClientCalls.blockingUnaryCall(channel, methodDescriptor, io.grpc.CallOptions.DEFAULT, ByteArray(0))
            } finally {
                scope.close()
                span.end()
            }

            latch.await(5, TimeUnit.SECONDS)
            rawChannel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
            otel.close()
        }

        val traceparent = captured.get().shouldNotBeNull()
        traceparent shouldStartWith "00-"
    }

    test("Kafka producer injects traceparent header and consumer extract recovers the same trace ID") {
        val exporter = InMemorySpanExporter.create()
        val otel = buildOtel(exporter)

        val headers = RecordHeaders()

        val producerSpan = otel.getTracer("test").spanBuilder("kafka-produce").startSpan()
        val originalTraceId = producerSpan.spanContext.traceId
        val producerScope = producerSpan.makeCurrent()
        try {
            OtelKafkaTracing.inject(Context.current(), headers, otel.propagators)
        } finally {
            producerScope.close()
            producerSpan.end()
        }

        // Consumer side: extract context from record headers
        val extractedContext = OtelKafkaTracing.extract(headers, otel.propagators)
        val extractedSpanContext = io.opentelemetry.api.trace.Span.fromContext(extractedContext).spanContext

        extractedSpanContext.isValid shouldBe true
        extractedSpanContext.traceId shouldBe originalTraceId

        otel.close()
    }
})

private object ByteArrayMarshaller : MethodDescriptor.Marshaller<ByteArray> {
    override fun stream(value: ByteArray): java.io.InputStream = value.inputStream()
    override fun parse(stream: java.io.InputStream): ByteArray = stream.readBytes()
}
