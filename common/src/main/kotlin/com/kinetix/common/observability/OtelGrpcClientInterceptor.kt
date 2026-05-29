package com.kinetix.common.observability

import io.grpc.ClientInterceptor
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry

/**
 * Factory for an OpenTelemetry gRPC client interceptor.
 *
 * Wraps [GrpcTelemetry] from `opentelemetry-grpc-1.6` to produce a [ClientInterceptor] that:
 * - Propagates W3C `traceparent` / `tracestate` into outbound gRPC metadata.
 * - Records client-side spans for every RPC call, linked to the enclosing trace.
 *
 * The interceptor must be registered on the [io.grpc.ManagedChannelBuilder] for each channel
 * the service opens. Per-service wiring is handled in kx-ybov.
 *
 * Usage:
 * ```kotlin
 * ManagedChannelBuilder
 *     .forAddress(host, port)
 *     .usePlaintext()
 *     .intercept(OtelGrpcClientInterceptor.newInterceptor(openTelemetry))
 *     .build()
 * ```
 */
object OtelGrpcClientInterceptor {

    /**
     * Creates a new gRPC [ClientInterceptor] that injects trace context and records RPC spans.
     *
     * @param openTelemetry The configured [OpenTelemetry] instance — typically the one built
     *   by [OtelInit.init] in the service's `Application.kt`.
     */
    fun newInterceptor(openTelemetry: OpenTelemetry): ClientInterceptor =
        GrpcTelemetry.create(openTelemetry).newClientInterceptor()
}
