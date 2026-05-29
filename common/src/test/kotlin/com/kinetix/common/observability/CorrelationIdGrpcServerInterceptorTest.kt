package com.kinetix.common.observability

import io.grpc.Channel
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerInterceptors
import io.grpc.ServerServiceDefinition
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
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
 * Tests that CorrelationIdGrpcServerInterceptor reads x-correlation-id from
 * inbound gRPC metadata and sets it on MDC for the duration of the handler.
 */
class CorrelationIdGrpcServerInterceptorTest : FunSpec({

    afterEach { MDC.remove(CorrelationIdContext.MDC_KEY) }

    test("inbound gRPC call with x-correlation-id sets MDC correlationId for the handler") {
        val correlationIdKey = Metadata.Key.of(CorrelationIdContext.GRPC_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)
        val capturedInHandler = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        val methodDescriptor: MethodDescriptor<ByteArray, ByteArray> = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.SvcA/Ping")
            .setRequestMarshaller(ByteArrayMarshaller2)
            .setResponseMarshaller(ByteArrayMarshaller2)
            .build()

        val serviceImpl = ServerCalls.asyncUnaryCall(
            ServerCalls.UnaryMethod<ByteArray, ByteArray> { _, observer ->
                capturedInHandler.set(MDC.get(CorrelationIdContext.MDC_KEY))
                latch.countDown()
                observer.onNext(ByteArray(0))
                observer.onCompleted()
            }
        )
        val serviceDefinition = ServerServiceDefinition.builder("test.SvcA")
            .addMethod(methodDescriptor, serviceImpl)
            .build()
        val interceptedService = ServerInterceptors.intercept(serviceDefinition, CorrelationIdGrpcServerInterceptor())

        val server = NettyServerBuilder.forPort(0).addService(interceptedService).build().start()
        val port = server.port

        try {
            val rawChannel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()
            // Attach a client-side header injector so we can send the metadata
            val headerInjector = object : io.grpc.ClientInterceptor {
                override fun <ReqT, RespT> interceptCall(
                    method: MethodDescriptor<ReqT, RespT>,
                    callOptions: io.grpc.CallOptions,
                    next: Channel,
                ): io.grpc.ClientCall<ReqT, RespT> {
                    return object : io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                        next.newCall(method, callOptions)
                    ) {
                        override fun start(responseListener: io.grpc.ClientCall.Listener<RespT>, headers: Metadata) {
                            headers.put(correlationIdKey, "server-side-id-xyz")
                            super.start(responseListener, headers)
                        }
                    }
                }
            }
            val channel = ClientInterceptors.intercept(rawChannel, headerInjector)

            ClientCalls.blockingUnaryCall(channel, methodDescriptor, io.grpc.CallOptions.DEFAULT, ByteArray(0))
            latch.await(5, TimeUnit.SECONDS)

            capturedInHandler.get() shouldBe "server-side-id-xyz"
            rawChannel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    test("inbound gRPC call without x-correlation-id leaves MDC correlationId unset in handler") {
        val capturedInHandler = AtomicReference<String?>("sentinel")
        val latch = CountDownLatch(1)

        val methodDescriptor: MethodDescriptor<ByteArray, ByteArray> = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.SvcB/Ping")
            .setRequestMarshaller(ByteArrayMarshaller2)
            .setResponseMarshaller(ByteArrayMarshaller2)
            .build()

        val serviceImpl = ServerCalls.asyncUnaryCall(
            ServerCalls.UnaryMethod<ByteArray, ByteArray> { _, observer ->
                capturedInHandler.set(MDC.get(CorrelationIdContext.MDC_KEY))
                latch.countDown()
                observer.onNext(ByteArray(0))
                observer.onCompleted()
            }
        )
        val serviceDefinition = ServerServiceDefinition.builder("test.SvcB")
            .addMethod(methodDescriptor, serviceImpl)
            .build()
        val interceptedService = ServerInterceptors.intercept(serviceDefinition, CorrelationIdGrpcServerInterceptor())

        val server = NettyServerBuilder.forPort(0).addService(interceptedService).build().start()
        val port = server.port

        try {
            MDC.remove(CorrelationIdContext.MDC_KEY)
            val rawChannel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()
            ClientCalls.blockingUnaryCall(rawChannel, methodDescriptor, io.grpc.CallOptions.DEFAULT, ByteArray(0))
            latch.await(5, TimeUnit.SECONDS)

            capturedInHandler.get() shouldBe null
            rawChannel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        }
    }
})

private object ByteArrayMarshaller2 : MethodDescriptor.Marshaller<ByteArray> {
    override fun stream(value: ByteArray): java.io.InputStream = value.inputStream()
    override fun parse(stream: java.io.InputStream): ByteArray = stream.readBytes()
}
