package com.kinetix.common.observability

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpSendPipeline
import io.ktor.util.AttributeKey
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter

/**
 * Ktor [HttpClientPlugin] that propagates the active OpenTelemetry span context into
 * outbound HTTP request headers using the W3C Trace Context format (`traceparent`, `tracestate`).
 *
 * When no span is active the propagator is a no-op — no headers are added. This means
 * requests fired outside of a traced context are invisible to the collector, which is
 * the correct behaviour: do not pollute the trace plane with unattributed traffic.
 *
 * Usage in a Ktor client:
 * ```kotlin
 * val client = HttpClient(CIO) {
 *     install(OtelHttpClientInterceptor) {
 *         openTelemetry = myOtelInstance
 *     }
 * }
 * ```
 */
class OtelHttpClientInterceptor private constructor(private val openTelemetry: OpenTelemetry) {

    class Config {
        var openTelemetry: OpenTelemetry = OpenTelemetry.noop()
    }

    companion object Plugin : HttpClientPlugin<Config, OtelHttpClientInterceptor> {

        override val key: AttributeKey<OtelHttpClientInterceptor> =
            AttributeKey("OtelHttpClientInterceptor")

        override fun prepare(block: Config.() -> Unit): OtelHttpClientInterceptor {
            val config = Config().apply(block)
            return OtelHttpClientInterceptor(config.openTelemetry)
        }

        override fun install(plugin: OtelHttpClientInterceptor, scope: HttpClient) {
            scope.sendPipeline.intercept(HttpSendPipeline.State) {
                val request = context as HttpRequestBuilder
                plugin.openTelemetry.propagators.textMapPropagator.inject(
                    Context.current(),
                    request,
                    HttpRequestBuilderSetter,
                )
                proceed()
            }
        }
    }
}

/**
 * [TextMapSetter] that writes trace context headers into a Ktor [HttpRequestBuilder].
 */
private object HttpRequestBuilderSetter : TextMapSetter<HttpRequestBuilder> {
    override fun set(carrier: HttpRequestBuilder?, key: String, value: String) {
        carrier?.headers?.set(key, value)
    }
}
