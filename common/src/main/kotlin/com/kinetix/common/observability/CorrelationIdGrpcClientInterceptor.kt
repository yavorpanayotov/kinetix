package com.kinetix.common.observability

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import org.slf4j.MDC

/**
 * gRPC client interceptor that propagates the current MDC correlation ID to
 * outbound gRPC calls via the `x-correlation-id` metadata key.
 *
 * Attach to a [Channel] at construction time alongside the OpenTelemetry
 * tracing interceptor:
 * ```kotlin
 * val channel = ClientInterceptors.intercept(
 *     managedChannel,
 *     RiskEngineTracingInterceptor.create(),
 *     CorrelationIdGrpcClientInterceptor(),
 * )
 * ```
 *
 * If there is no current MDC value the metadata key is not sent.
 */
class CorrelationIdGrpcClientInterceptor : ClientInterceptor {

    private val metadataKey: Metadata.Key<String> =
        Metadata.Key.of(CorrelationIdContext.GRPC_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val correlationId = MDC.get(CorrelationIdContext.MDC_KEY)

        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions),
        ) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                if (correlationId != null) {
                    headers.put(metadataKey, correlationId)
                }
                super.start(responseListener, headers)
            }
        }
    }
}
