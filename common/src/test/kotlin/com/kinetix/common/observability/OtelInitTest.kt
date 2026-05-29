package com.kinetix.common.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter

class OtelInitTest : FunSpec({

    test("init returns an OpenTelemetrySdk instance when an OTLP endpoint is supplied") {
        val otel = OtelInit.init(
            serviceName = "test-service",
            otlpEndpoint = "http://localhost:4317",
        )
        try {
            otel.shouldBeInstanceOf<OpenTelemetrySdk>()
        } finally {
            (otel as? OpenTelemetrySdk)?.close()
        }
    }

    test("init sets service.name resource attribute on emitted spans") {
        val exporter = InMemorySpanExporter.create()
        val otel = OtelInit.init(
            serviceName = "my-service",
            otlpEndpoint = "http://localhost:4317",
            spanExporterOverride = exporter,
        )
        try {
            otel.getTracer("test").spanBuilder("probe").startSpan().end()

            val spans = exporter.finishedSpanItems
            spans.size shouldBe 1
            spans[0].resource.attributes
                .get(io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME) shouldBe "my-service"
        } finally {
            (otel as? OpenTelemetrySdk)?.close()
        }
    }

    test("init sets service.namespace to kinetix on emitted spans") {
        val exporter = InMemorySpanExporter.create()
        val otel = OtelInit.init(
            serviceName = "any-service",
            otlpEndpoint = "http://localhost:4317",
            spanExporterOverride = exporter,
        )
        try {
            otel.getTracer("test").spanBuilder("probe").startSpan().end()

            val spans = exporter.finishedSpanItems
            spans.size shouldBe 1
            spans[0].resource.attributes
                .get(io.opentelemetry.semconv.ResourceAttributes.SERVICE_NAMESPACE) shouldBe "kinetix"
        } finally {
            (otel as? OpenTelemetrySdk)?.close()
        }
    }

    test("init returns noop OpenTelemetry when no endpoint is supplied and env var is absent") {
        val otel = OtelInit.init(serviceName = "noop-service", otlpEndpoint = null)

        // Noop instance must not be OpenTelemetrySdk — it's the global noop singleton
        otel.shouldNotBeInstanceOf<OpenTelemetrySdk>()
        // Must be usable without error
        otel.getTracer("test").spanBuilder("span").startSpan().end()
    }

    test("multiple spans are exported when spanExporterOverride is wired") {
        val exporter = InMemorySpanExporter.create()
        val otel = OtelInit.init(
            serviceName = "multi-span-service",
            otlpEndpoint = "http://localhost:4317",
            spanExporterOverride = exporter,
        )
        try {
            val tracer = otel.getTracer("test")
            repeat(3) { tracer.spanBuilder("op-$it").startSpan().end() }

            // SimpleSpanProcessor exports synchronously — no flush needed
            exporter.finishedSpanItems.size shouldBe 3
        } finally {
            (otel as? OpenTelemetrySdk)?.close()
        }
    }
})
