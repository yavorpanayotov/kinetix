package com.kinetix.common.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking

class OtelHttpClientInterceptorTest : FunSpec({

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

    test("traceparent header is injected on outbound HTTP requests when a span is active") {
        var capturedTraceparent: String? = null

        val mockEngine = MockEngine { request ->
            capturedTraceparent = request.headers["traceparent"]
            respond("ok", HttpStatusCode.OK)
        }

        val client = HttpClient(mockEngine) {
            install(OtelHttpClientInterceptor) {
                openTelemetry = otel
            }
        }

        runBlocking {
            val tracer = otel.getTracer("test")
            val span = tracer.spanBuilder("outer-span").startSpan()
            val scope = span.makeCurrent()
            try {
                client.get("http://example.com/api")
            } finally {
                scope.close()
                span.end()
            }
        }

        capturedTraceparent.shouldNotBeNull()
        capturedTraceparent!! shouldStartWith "00-"
    }

    test("traceparent header is absent on outbound HTTP requests when no span is active") {
        var capturedTraceparent: String? = null

        val mockEngine = MockEngine { request ->
            capturedTraceparent = request.headers["traceparent"]
            respond("ok", HttpStatusCode.OK)
        }

        val client = HttpClient(mockEngine) {
            install(OtelHttpClientInterceptor) {
                openTelemetry = otel
            }
        }

        runBlocking {
            client.get("http://example.com/api")
        }

        // No active span — propagator injects nothing meaningful (no valid trace context)
        // Either null or the header is absent; the W3C propagator does not inject invalid context
        assert(capturedTraceparent == null || capturedTraceparent!!.isBlank()) {
            "Expected no traceparent when no span is active, but got: $capturedTraceparent"
        }
    }
})
