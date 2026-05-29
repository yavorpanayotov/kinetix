package com.kinetix.common.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.apache.kafka.common.header.internals.RecordHeaders

class OtelKafkaTracingTest : FunSpec({

    lateinit var otel: OpenTelemetrySdk
    lateinit var exporter: InMemorySpanExporter

    beforeEach {
        exporter = InMemorySpanExporter.create()
        otel = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            )
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()
    }

    afterEach {
        otel.close()
        exporter.reset()
    }

    test("inject writes traceparent header into Kafka RecordHeaders when span is active") {
        val headers = RecordHeaders()
        val span = otel.getTracer("test").spanBuilder("producer-span").startSpan()
        val scope = span.makeCurrent()
        try {
            OtelKafkaTracing.inject(Context.current(), headers, otel.propagators)
        } finally {
            scope.close()
            span.end()
        }

        val traceparent = headers.lastHeader("traceparent")
        traceparent.shouldNotBeNull()
        val value = String(traceparent.value(), Charsets.UTF_8)
        value shouldStartWith "00-"
    }

    test("inject does not write traceparent header when no span is active") {
        val headers = RecordHeaders()
        OtelKafkaTracing.inject(Context.current(), headers, otel.propagators)

        // W3C propagator does not inject when no valid span context is present
        val traceparent = headers.lastHeader("traceparent")
        traceparent.shouldBeNull()
    }

    test("extract returns a context containing the original span context from Kafka headers") {
        // Produce a span and capture its traceparent
        val headers = RecordHeaders()
        val originalSpan = otel.getTracer("test").spanBuilder("original").startSpan()
        val originalTraceId = originalSpan.spanContext.traceId
        val originalScope = originalSpan.makeCurrent()
        try {
            OtelKafkaTracing.inject(Context.current(), headers, otel.propagators)
        } finally {
            originalScope.close()
            originalSpan.end()
        }

        // Extract on consumer side
        val extractedContext = OtelKafkaTracing.extract(headers, otel.propagators)
        val extractedSpan = Span.fromContext(extractedContext)

        extractedSpan.spanContext.isValid shouldBe true
        extractedSpan.spanContext.traceId shouldBe originalTraceId
    }

    test("extract returns an empty context when no traceparent header is present") {
        val headers = RecordHeaders()
        val extractedContext = OtelKafkaTracing.extract(headers, otel.propagators)
        val span = Span.fromContext(extractedContext)

        // No valid span context — should be invalid (noop span)
        span.spanContext.isValid shouldBe false
    }
})
