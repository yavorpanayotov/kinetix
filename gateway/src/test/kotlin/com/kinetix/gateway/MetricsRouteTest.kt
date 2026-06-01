package com.kinetix.gateway

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*

class MetricsRouteTest : FunSpec({

    test("GET /metrics returns 200 OK") {
        testApplication {
            application { module() }
            val response = client.get("/metrics")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("GET /metrics returns Prometheus exposition format") {
        testApplication {
            application { module() }
            val response = client.get("/metrics")
            response.bodyAsText() shouldContain "# HELP"
        }
    }

    test("GET /metrics includes JVM metrics") {
        testApplication {
            application { module() }
            val response = client.get("/metrics")
            response.bodyAsText() shouldContain "jvm_memory"
        }
    }

    // The gateway dashboard's latency panels (Max P95, Latency Percentiles by
    // Route, Upstream Service-Call Latency) use
    // histogram_quantile(..., ktor_http_server_requests_seconds_bucket). Those
    // `_bucket` series only exist if the HTTP server timer publishes a
    // percentile histogram — without it the panels read "No data" (kx-cygh).
    test("GET /metrics publishes ktor http server request histogram buckets") {
        testApplication {
            application { module() }
            // Serve at least one request so the server timer records a sample.
            client.get("/health")
            val response = client.get("/metrics")
            response.bodyAsText() shouldContain "ktor_http_server_requests_seconds_bucket"
        }
    }
})
