package com.kinetix.common.observability

import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor

/**
 * gRPC server interceptor that extracts `x-correlation-id` from inbound call
 * metadata and places it in MDC under [CorrelationIdContext.MDC_KEY] for the
 * duration of the call.
 *
 * If the metadata key is absent, the MDC entry is left unchanged (typically
 * absent for autonomous scheduled calls that originate without a user request).
 *
 * Register on every gRPC server that Kinetix services expose:
 * ```kotlin
 * val server = NettyServerBuilder.forPort(port)
 *     .addService(ServerInterceptors.intercept(serviceImpl, CorrelationIdGrpcServerInterceptor()))
 *     .build()
 * ```
 */
class CorrelationIdGrpcServerInterceptor : ServerInterceptor {

    private val metadataKey: Metadata.Key<String> =
        Metadata.Key.of(CorrelationIdContext.GRPC_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val correlationId = headers.get(metadataKey)
        return if (correlationId != null) {
            // Wrap the call so MDC is scoped to this gRPC call's thread.
            // For unary calls the listener methods are invoked on a gRPC
            // thread pool thread. We install the ID on the onMessage callback
            // (where the handler runs) via runWithCorrelationId.
            CorrelationIdServerCallListener(
                next.startCall(call, headers),
                correlationId,
            )
        } else {
            next.startCall(call, headers)
        }
    }

    private class CorrelationIdServerCallListener<ReqT>(
        delegate: ServerCall.Listener<ReqT>,
        private val correlationId: String,
    ) : io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {

        override fun onMessage(message: ReqT) {
            CorrelationIdContext.runWithCorrelationId(correlationId) {
                super.onMessage(message)
            }
        }

        override fun onHalfClose() {
            CorrelationIdContext.runWithCorrelationId(correlationId) {
                super.onHalfClose()
            }
        }

        override fun onComplete() {
            CorrelationIdContext.runWithCorrelationId(correlationId) {
                super.onComplete()
            }
        }
    }
}
