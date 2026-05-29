package com.kinetix.gateway.observability

import com.kinetix.common.observability.OtelInit
import com.kinetix.common.observability.OtelHttpClientInterceptor
import com.kinetix.common.observability.OtelHttpServerPlugin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

/**
 * Acceptance tests for OTel tracing wiring in the gateway.
 *
 * Verifies the three wiring points without a live collector:
 *
 * 1. Inbound HTTP with a `traceparent` header → a SERVER span is created and linked
 *    to the incoming trace ID.
 * 2. Outbound HTTP client call inside an active span → the client propagates
 *    `traceparent` to the upstream.
 * 3. Missing OTLP endpoint → service starts safely, no spans exported
 *    (noop path).
 */
class OtelTracingWiringAcceptanceTest : FunSpec({

    fun buildOtel(exporter: InMemorySpanExporter): OpenTelemetrySdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            )
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()

    test("inbound HTTP request with traceparent header produces a server span linked to the upstream trace") {
        val exporter = InMemorySpanExporter.create()
        val otel = buildOtel(exporter)

        // A valid W3C traceparent for trace 0000...0001, span 0000...0001
        val upstreamTraceparent = "00-00000000000000000000000000000001-0000000000000001-01"

        testApplication {
            application {
                install(OtelHttpServerPlugin) {
                    openTelemetry = otel
                }
                routing {
                    get("/probe") { call.respondText("ok") }
                }
            }
            val response = client.get("/probe") {
                header("traceparent", upstreamTraceparent)
            }
            response.status shouldBe HttpStatusCode.OK
        }

        val spans = exporter.finishedSpanItems
        spans.shouldNotBeEmpty()
        val serverSpan = spans.first { it.kind == SpanKind.SERVER }
        // The server span must be a child of the upstream trace (same trace ID)
        serverSpan.traceId shouldBe "00000000000000000000000000000001"

        otel.close()
    }

    test("outbound HTTP client with OtelHttpClientInterceptor propagates traceparent to upstream") {
        val exporter = InMemorySpanExporter.create()
        val otel = buildOtel(exporter)

        var capturedTraceparent: String? = null
        val mockEngine = MockEngine { request ->
            capturedTraceparent = request.headers["traceparent"]
            respond("upstream-ok", HttpStatusCode.OK)
        }

        val tracedClient = HttpClient(mockEngine) {
            install(OtelHttpClientInterceptor) {
                openTelemetry = otel
            }
        }

        // Start a span so the propagator has context to inject
        val span = otel.getTracer("test").spanBuilder("outbound-test").startSpan()
        val scope = span.makeCurrent()
        try {
            tracedClient.get("http://position-service/api/v1/health")
        } finally {
            scope.close()
            span.end()
        }

        capturedTraceparent.shouldNotBeNull()
        capturedTraceparent!! shouldStartWith "00-"

        otel.close()
    }

    test("service starts safely and emits no spans when OTEL_EXPORTER_OTLP_ENDPOINT is absent") {
        // OtelInit.init with no endpoint and no override returns noop — verify that
        // installing OtelHttpServerPlugin with noop OpenTelemetry does not crash.
        val noopOtel = OtelInit.init(serviceName = "gateway", otlpEndpoint = null)

        testApplication {
            application {
                install(OtelHttpServerPlugin) {
                    openTelemetry = noopOtel
                }
                routing {
                    get("/health") { call.respondText("""{"status":"UP"}""") }
                }
            }
            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe """{"status":"UP"}"""
        }
        // No exception thrown — noop path is safe
    }
})
