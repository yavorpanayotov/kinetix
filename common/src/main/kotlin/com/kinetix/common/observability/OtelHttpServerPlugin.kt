package com.kinetix.common.observability

import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter

/**
 * Ktor server plugin that captures inbound HTTP requests as OpenTelemetry server spans.
 *
 * On each inbound call the plugin:
 * 1. Extracts the W3C `traceparent` / `tracestate` headers from the request using the
 *    [OpenTelemetry] propagators configured in [OtelHttpServerPluginConfig.openTelemetry].
 * 2. Starts a [SpanKind.SERVER] span as a child of the extracted context (or as a new
 *    root span if no valid trace context is present in the request headers).
 * 3. Makes the span current for the remainder of the call so downstream code
 *    (outbound HTTP, gRPC, Kafka publish) can pick it up as the parent.
 * 4. Ends the span (and closes the scope) when the response is sent.
 *
 * This approach mirrors the [CorrelationIdHttpServerPlugin] pattern: a manual
 * `createApplicationPlugin` hook rather than the alpha
 * `opentelemetry-ktor-3.x` artifact. No additional library dependency is required beyond
 * what [OtelInit] already pulls in.
 *
 * Usage:
 * ```kotlin
 * install(OtelHttpServerPlugin) {
 *     openTelemetry = myOtelInstance
 *     tracerName = "my-service"
 * }
 * ```
 *
 * When [OtelHttpServerPluginConfig.openTelemetry] is [OpenTelemetry.noop] (the
 * default when no OTLP endpoint is configured), span creation is a no-op — services
 * start safely without a collector.
 */
val OtelHttpServerPlugin: ApplicationPlugin<OtelHttpServerPluginConfig> = createApplicationPlugin(
    name = "OtelHttpServerPlugin",
    createConfiguration = ::OtelHttpServerPluginConfig,
) {
    val otel = pluginConfig.openTelemetry
    val tracerName = pluginConfig.tracerName

    on(CallSetup) { call ->
        val request = call.request
        val parentContext = otel.propagators.textMapPropagator.extract(
            Context.current(),
            request,
            KtorServerRequestGetter,
        )

        val spanName = "${request.httpMethod.value} ${request.path()}"
        val span = otel.getTracer(tracerName)
            .spanBuilder(spanName)
            .setParent(parentContext)
            .setSpanKind(SpanKind.SERVER)
            .startSpan()

        val scope = span.makeCurrent()
        call.attributes.put(OtelSpanKey, span)
        call.attributes.put(OtelScopeKey, scope)
    }

    on(ResponseSent) { call ->
        call.attributes.getOrNull(OtelScopeKey)?.close()
        call.attributes.getOrNull(OtelSpanKey)?.end()
    }
}

/**
 * Configuration block for [OtelHttpServerPlugin].
 */
class OtelHttpServerPluginConfig {
    /** The [OpenTelemetry] instance — defaults to noop so missing config is safe. */
    var openTelemetry: OpenTelemetry = OpenTelemetry.noop()

    /** The instrumentation scope name used when creating spans. */
    var tracerName: String = "kinetix.ktor.server"
}

/**
 * Attribute key for the active [io.opentelemetry.api.trace.Span] on a call.
 * Stored so [ResponseSent] can end the span regardless of the response path taken.
 */
private val OtelSpanKey =
    io.ktor.util.AttributeKey<io.opentelemetry.api.trace.Span>("OtelSpan")

/**
 * Attribute key for the active [io.opentelemetry.context.Scope] on a call.
 */
private val OtelScopeKey =
    io.ktor.util.AttributeKey<io.opentelemetry.context.Scope>("OtelScope")

/**
 * [TextMapGetter] that reads header values from a Ktor server request.
 */
private object KtorServerRequestGetter : TextMapGetter<io.ktor.server.request.ApplicationRequest> {

    override fun keys(carrier: io.ktor.server.request.ApplicationRequest): Iterable<String> =
        carrier.headers.names()

    override fun get(carrier: io.ktor.server.request.ApplicationRequest?, key: String): String? =
        carrier?.headers?.get(key)
}
