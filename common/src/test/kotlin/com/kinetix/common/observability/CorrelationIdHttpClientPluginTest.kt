package com.kinetix.common.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.slf4j.MDC

class CorrelationIdHttpClientPluginTest : FunSpec({

    afterEach { MDC.remove(CorrelationIdContext.MDC_KEY) }

    test("outbound request carries X-Correlation-ID header from current MDC correlationId") {
        MDC.put(CorrelationIdContext.MDC_KEY, "outbound-id-789")
        var capturedHeader: String? = null

        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedHeader = request.headers["X-Correlation-ID"]
                    respond("ok", HttpStatusCode.OK)
                }
            }
            install(CorrelationIdHttpClientPlugin)
        }

        client.get("http://localhost/test")
        capturedHeader shouldBe "outbound-id-789"
        client.close()
    }

    test("outbound request has no X-Correlation-ID header when MDC is empty") {
        MDC.remove(CorrelationIdContext.MDC_KEY)
        var capturedHeader: String? = "not-set-sentinel"

        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedHeader = request.headers["X-Correlation-ID"]
                    respond("ok", HttpStatusCode.OK)
                }
            }
            install(CorrelationIdHttpClientPlugin)
        }

        client.get("http://localhost/test")
        capturedHeader shouldBe null
        client.close()
    }

    test("outbound request does not override an explicitly set X-Correlation-ID header") {
        MDC.put(CorrelationIdContext.MDC_KEY, "mdc-id")
        var capturedHeader: String? = null

        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedHeader = request.headers["X-Correlation-ID"]
                    respond("ok", HttpStatusCode.OK)
                }
            }
            install(CorrelationIdHttpClientPlugin)
        }

        client.get("http://localhost/test") {
            header("X-Correlation-ID", "caller-set-id")
        }
        // Plugin must not clobber an already-set header
        capturedHeader shouldBe "caller-set-id"
        client.close()
    }
})
