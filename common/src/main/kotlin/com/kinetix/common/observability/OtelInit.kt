package com.kinetix.common.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.semconv.ResourceAttributes

/**
 * Entry point for OpenTelemetry SDK initialisation in Kinetix services.
 *
 * Responsibilities:
 * - Builds a [Resource] with `service.name` and `service.namespace = "kinetix"`.
 * - Wires a [BatchSpanProcessor] to an OTLP/gRPC span exporter.
 * - Returns [OpenTelemetry.noop] when no OTLP endpoint is available so services
 *   can start safely in environments without a collector (e.g. local dev without infra).
 *
 * Per-service wiring (installing the SDK globally, configuring Ktor plugins, etc.) is done
 * in each service's `Application.kt` — this object only builds the SDK instance.
 */
object OtelInit {

    /**
     * Initialises the OpenTelemetry SDK and returns a configured [OpenTelemetry] instance.
     *
     * @param serviceName The `service.name` resource attribute — typically the Kotlin module name.
     * @param otlpEndpoint The OTLP/gRPC collector endpoint (e.g. `http://otel-collector:4317`).
     *   When `null`, falls back to the `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable.
     *   When both are absent, returns [OpenTelemetry.noop].
     * @param spanExporterOverride Injects a custom [SpanExporter] — used in tests to capture
     *   spans in-memory without requiring a live collector.
     */
    fun init(
        serviceName: String,
        otlpEndpoint: String? = null,
        spanExporterOverride: SpanExporter? = null,
    ): OpenTelemetry {
        val resolvedEndpoint = otlpEndpoint
            ?: System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")

        if (resolvedEndpoint == null && spanExporterOverride == null) {
            return OpenTelemetry.noop()
        }

        val resource = Resource.getDefault().merge(
            Resource.create(
                io.opentelemetry.api.common.Attributes.of(
                    ResourceAttributes.SERVICE_NAME, serviceName,
                    ResourceAttributes.SERVICE_NAMESPACE, "kinetix",
                )
            )
        )

        val exporter: SpanExporter = spanExporterOverride
            ?: OtlpGrpcSpanExporter.builder()
                .setEndpoint(resolvedEndpoint!!)
                .build()

        val spanProcessor = if (spanExporterOverride != null) {
            // Use SimpleSpanProcessor in tests so spans are synchronously exported
            // without requiring an explicit forceFlush call.
            SimpleSpanProcessor.create(exporter)
        } else {
            BatchSpanProcessor.builder(exporter).build()
        }

        val tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(spanProcessor)
            .build()

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(
                io.opentelemetry.context.propagation.ContextPropagators.create(
                    io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()
                )
            )
            .build()
    }
}
