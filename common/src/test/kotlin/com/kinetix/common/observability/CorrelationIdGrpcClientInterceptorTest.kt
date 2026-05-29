package com.kinetix.common.observability

import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
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
import io.grpc.stub.AbstractStub
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.slf4j.MDC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests that CorrelationIdGrpcClientInterceptor propagates the current MDC
 * correlationId into outbound gRPC call metadata as `x-correlation-id`.
 *
 * Uses a real in-JVM Netty gRPC server (bound to port 0) and a capturing
 * server interceptor so the test verifies actual gRPC wire transport.
 */
class CorrelationIdGrpcClientInterceptorTest : FunSpec({

    afterEach { MDC.remove(CorrelationIdContext.MDC_KEY) }

    test("outbound gRPC call carries x-correlation-id metadata when MDC contains correlationId") {
        val correlationIdKey = Metadata.Key.of(CorrelationIdContext.GRPC_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)
        val captured = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        // Minimal unary gRPC method that records the inbound metadata
        val methodDescriptor: MethodDescriptor<ByteArray, ByteArray> = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.TestService/Ping")
            .setRequestMarshaller(ByteArrayMarshaller)
            .setResponseMarshaller(ByteArrayMarshaller)
            .build()

        val capturingInterceptor = object : ServerInterceptor {
            override fun <Req, Resp> interceptCall(
                call: ServerCall<Req, Resp>,
                headers: Metadata,
                next: ServerCallHandler<Req, Resp>,
            ): ServerCall.Listener<Req> {
                captured.set(headers.get(correlationIdKey))
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
        val serviceDefinition = ServerServiceDefinition.builder("test.TestService")
            .addMethod(methodDescriptor, serviceImpl)
            .build()
        val interceptedService = ServerInterceptors.intercept(serviceDefinition, capturingInterceptor)

        val server = NettyServerBuilder.forPort(0).addService(interceptedService).build().start()
        val port = server.port

        try {
            MDC.put(CorrelationIdContext.MDC_KEY, "grpc-correlation-abc")

            val rawChannel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()
            val channel = ClientInterceptors.intercept(rawChannel, CorrelationIdGrpcClientInterceptor())

            ClientCalls.blockingUnaryCall(channel, methodDescriptor, io.grpc.CallOptions.DEFAULT, ByteArray(0))

            latch.await(5, TimeUnit.SECONDS)
            captured.get() shouldBe "grpc-correlation-abc"

            rawChannel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    test("outbound gRPC call has no x-correlation-id metadata when MDC is empty") {
        val correlationIdKey = Metadata.Key.of(CorrelationIdContext.GRPC_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)
        val captured = AtomicReference<String?>("sentinel")
        val latch = CountDownLatch(1)

        val methodDescriptor: MethodDescriptor<ByteArray, ByteArray> = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.TestService2/Ping")
            .setRequestMarshaller(ByteArrayMarshaller)
            .setResponseMarshaller(ByteArrayMarshaller)
            .build()

        val capturingInterceptor = object : ServerInterceptor {
            override fun <Req, Resp> interceptCall(
                call: ServerCall<Req, Resp>,
                headers: Metadata,
                next: ServerCallHandler<Req, Resp>,
            ): ServerCall.Listener<Req> {
                captured.set(headers.get(correlationIdKey))
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
        val serviceDefinition = ServerServiceDefinition.builder("test.TestService2")
            .addMethod(methodDescriptor, serviceImpl)
            .build()
        val interceptedService = ServerInterceptors.intercept(serviceDefinition, capturingInterceptor)

        val server = NettyServerBuilder.forPort(0).addService(interceptedService).build().start()
        val port = server.port

        try {
            MDC.remove(CorrelationIdContext.MDC_KEY)

            val rawChannel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()
            val channel = ClientInterceptors.intercept(rawChannel, CorrelationIdGrpcClientInterceptor())

            ClientCalls.blockingUnaryCall(channel, methodDescriptor, io.grpc.CallOptions.DEFAULT, ByteArray(0))

            latch.await(5, TimeUnit.SECONDS)
            captured.get() shouldBe null

            rawChannel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        }
    }
})

/** Trivial Metadata marshaller for byte arrays — test infrastructure only. */
private object ByteArrayMarshaller : MethodDescriptor.Marshaller<ByteArray> {
    override fun stream(value: ByteArray): java.io.InputStream = value.inputStream()
    override fun parse(stream: java.io.InputStream): ByteArray = stream.readBytes()
}
