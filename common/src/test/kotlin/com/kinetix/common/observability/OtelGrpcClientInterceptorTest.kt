package com.kinetix.common.observability

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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldStartWith
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies that [OtelGrpcClientInterceptor] injects W3C trace context into gRPC metadata.
 *
 * Uses an in-JVM Netty gRPC server (NettyServerBuilder on port 0) — no Testcontainers —
 * so HTTP/2 transport, serialization, and interceptor wiring are exercised for real.
 * Follows the pattern established by [CorrelationIdGrpcClientInterceptorTest].
 */
class OtelGrpcClientInterceptorTest : FunSpec({

    val methodDescriptor: MethodDescriptor<ByteArray, ByteArray> = MethodDescriptor
        .newBuilder<ByteArray, ByteArray>()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("test.OtelTestService/Ping")
        .setRequestMarshaller(OtelTestByteArrayMarshaller)
        .setResponseMarshaller(OtelTestByteArrayMarshaller)
        .build()

    test("traceparent metadata key is present on server when span is active on client") {
        val traceparentKey = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER)
        val captured = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        // Server interceptor that records the inbound traceparent header
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
        val serviceDefinition = ServerServiceDefinition.builder("test.OtelTestService")
            .addMethod(methodDescriptor, serviceImpl)
            .build()
        val interceptedService = ServerInterceptors.intercept(serviceDefinition, capturingInterceptor)

        val server = NettyServerBuilder.forPort(0).addService(interceptedService).build().start()
        val port = server.port

        val exporter = InMemorySpanExporter.create()
        val otel = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            )
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()

        try {
            val rawChannel = ManagedChannelBuilder
                .forAddress("localhost", port)
                .usePlaintext()
                .build()
            val channel = ClientInterceptors.intercept(
                rawChannel,
                OtelGrpcClientInterceptor.newInterceptor(otel)
            )

            val span = otel.getTracer("test").spanBuilder("client-span").startSpan()
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
})

/** Trivial byte-array marshaller for gRPC test infrastructure. */
private object OtelTestByteArrayMarshaller : MethodDescriptor.Marshaller<ByteArray> {
    override fun stream(value: ByteArray): java.io.InputStream = value.inputStream()
    override fun parse(stream: java.io.InputStream): ByteArray = stream.readBytes()
}
