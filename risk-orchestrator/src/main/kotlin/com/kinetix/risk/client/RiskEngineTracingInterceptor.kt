package com.kinetix.risk.client

import io.grpc.ClientInterceptor
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry

/**
 * Builds the OpenTelemetry gRPC client interceptor used for outbound calls to the
 * Python risk-engine.
 *
 * Attaching the returned interceptor to the risk-engine [io.grpc.ManagedChannel]
 * (or stub) makes every gRPC call inject the active span context into outbound
 * call metadata as a W3C `traceparent` header. The Python engine — instrumented
 * with `opentelemetry-instrumentation-grpc` — extracts that header and continues
 * the same trace, so risk-orchestrator → risk-engine traces stitch together in
 * Tempo instead of terminating at the Kotlin boundary.
 *
 * The factory keeps OTel wiring out of [GrpcRiskEngineClient] itself: the client
 * stays focused on request/response mapping, and the interceptor is supplied at
 * channel-construction time.
 */
object RiskEngineTracingInterceptor {

    /**
     * Creates a gRPC client interceptor that propagates the W3C `traceparent`
     * header on every outbound call.
     *
     * @param openTelemetry the OpenTelemetry instance to use. Defaults to
     *   [GlobalOpenTelemetry.get], which is the no-op instance until the SDK is
     *   installed (see `AutoConfigOpenTelemetryAppender`) — matching how the rest
     *   of the module obtains OpenTelemetry without a DI-provided instance.
     */
    fun create(openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get()): ClientInterceptor =
        GrpcTelemetry.create(openTelemetry).newClientInterceptor()
}
