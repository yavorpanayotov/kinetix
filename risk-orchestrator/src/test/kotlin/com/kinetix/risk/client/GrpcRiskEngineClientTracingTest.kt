package com.kinetix.risk.client

import com.google.protobuf.Timestamp
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.proto.risk.RiskCalculationServiceGrpcKt
import com.kinetix.proto.risk.RiskCalculationServiceGrpcKt.RiskCalculationServiceCoroutineStub
import com.kinetix.proto.risk.RiskCalculationType
import com.kinetix.proto.risk.VaRRequest
import com.kinetix.proto.risk.VaRResponse
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.VaRCalculationRequest
import io.grpc.ClientInterceptors
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.math.BigDecimal
import java.util.Currency
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import com.kinetix.proto.common.BookId as ProtoBookId
import com.kinetix.proto.risk.ConfidenceLevel as ProtoConfidenceLevelEnum

private val USD = Currency.getInstance("USD")

private val TRACEPARENT_KEY: Metadata.Key<String> =
    Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER)

/**
 * Server-side interceptor that records the inbound [Metadata] of every gRPC call,
 * so the test can inspect what headers the client actually put on the wire.
 */
private class MetadataCapturingInterceptor : ServerInterceptor {
    val capturedHeaders: MutableList<Metadata> = CopyOnWriteArrayList()

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        capturedHeaders += headers
        return next.startCall(call, headers)
    }
}

/**
 * Fake risk-engine `RiskCalculationService` that returns a fixed VaR response.
 */
private class FixedRiskCalculationService :
    RiskCalculationServiceGrpcKt.RiskCalculationServiceCoroutineImplBase() {

    override suspend fun calculateVaR(request: VaRRequest): VaRResponse =
        VaRResponse.newBuilder()
            .setBookId(ProtoBookId.newBuilder().setValue(request.bookId.value))
            .setCalculationType(RiskCalculationType.PARAMETRIC)
            .setConfidenceLevel(ProtoConfidenceLevelEnum.CL_95)
            .setVarValue(1000.0)
            .setExpectedShortfall(1200.0)
            .setCalculatedAt(Timestamp.newBuilder().setSeconds(1700000000))
            .build()
}

/**
 * Wire-level proof that [GrpcRiskEngineClient] propagates the active trace context
 * to the risk-engine. A fake risk-engine `ServiceImplBase` is bound to an in-JVM
 * Netty gRPC server on a random port; a server-side interceptor captures inbound
 * metadata. With the OpenTelemetry gRPC client interceptor attached to the channel
 * and an active span on the calling context, the inbound metadata must carry a
 * W3C `traceparent` header — which is what lets risk-engine continue the trace.
 */
class GrpcRiskEngineClientTracingTest : FunSpec({

    test("propagates a W3C traceparent header to the risk-engine on every gRPC call") {
        val capturing = MetadataCapturingInterceptor()
        val server = NettyServerBuilder.forPort(0)
            .addService(FixedRiskCalculationService())
            .intercept(capturing)
            .build()
            .start()

        // SDK with a real, recording tracer so spans have a valid (non-zero)
        // trace context that the gRPC instrumentation can inject.
        val spanExporter = InMemorySpanExporter.create()
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build()
        // W3C trace-context propagator — without it the SDK's propagators are
        // a no-op and the gRPC instrumentation injects nothing. Production uses
        // the autoconfigured SDK, which defaults to W3C propagation.
        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()

        try {
            val baseChannel = io.grpc.ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .build()
            // Same wiring as Application.kt: the OTel gRPC client interceptor
            // wraps the channel so traceparent is injected on outbound calls.
            val channel = ClientInterceptors.intercept(
                baseChannel,
                RiskEngineTracingInterceptor.create(openTelemetry),
            )
            val client = GrpcRiskEngineClient(RiskCalculationServiceCoroutineStub(channel))

            val span = openTelemetry.getTracer("test").spanBuilder("orchestrator-var").startSpan()
            try {
                span.makeCurrent().use {
                    client.calculateVaR(
                        VaRCalculationRequest(
                            bookId = BookId("port-1"),
                            calculationType = CalculationType.PARAMETRIC,
                            confidenceLevel = ConfidenceLevel.CL_95,
                            timeHorizonDays = 1,
                            numSimulations = 10_000,
                        ),
                        listOf(
                            Position(
                                bookId = BookId("port-1"),
                                instrumentId = InstrumentId("AAPL"),
                                assetClass = AssetClass.EQUITY,
                                quantity = BigDecimal("100"),
                                averageCost = Money(BigDecimal("150.00"), USD),
                                marketPrice = Money(BigDecimal("170.00"), USD),
                                instrumentType = InstrumentTypeCode.CASH_EQUITY,
                            ),
                        ),
                    )
                }
            } finally {
                span.end()
            }

            baseChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS)

            capturing.capturedHeaders.size shouldBe 1
            val traceparent = capturing.capturedHeaders.single().get(TRACEPARENT_KEY)
            traceparent.shouldNotBeNull()
            // W3C trace-context: version "00", 32-hex trace id, 16-hex span id, 2-hex flags.
            traceparent shouldMatch Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
            // The propagated trace id must be the one from the orchestrator's span.
            traceparent.split("-")[1] shouldBe span.spanContext.traceId
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            tracerProvider.close()
        }
    }
})
