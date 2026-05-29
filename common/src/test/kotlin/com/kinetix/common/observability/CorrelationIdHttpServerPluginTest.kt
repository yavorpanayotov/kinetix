package com.kinetix.common.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.slf4j.MDC

class CorrelationIdHttpServerPluginTest : FunSpec({

    afterEach { MDC.remove(CorrelationIdContext.MDC_KEY) }

    test("request without X-Correlation-ID header gets a fresh UUID set in MDC") {
        var capturedFromMdc: String? = null
        testApplication {
            application {
                install(CorrelationIdHttpServerPlugin)
                routing {
                    get("/ping") {
                        capturedFromMdc = MDC.get(CorrelationIdContext.MDC_KEY)
                        call.respond(HttpStatusCode.OK, "pong")
                    }
                }
            }
            client.get("/ping")
        }
        capturedFromMdc shouldNotBe null
        capturedFromMdc!!.isNotBlank() shouldBe true
    }

    test("request with X-Correlation-ID header preserves the supplied value in MDC") {
        var capturedFromMdc: String? = null
        testApplication {
            application {
                install(CorrelationIdHttpServerPlugin)
                routing {
                    get("/ping") {
                        capturedFromMdc = MDC.get(CorrelationIdContext.MDC_KEY)
                        call.respond(HttpStatusCode.OK, "pong")
                    }
                }
            }
            client.get("/ping") {
                header("X-Correlation-ID", "supplied-id-123")
            }
        }
        capturedFromMdc shouldBe "supplied-id-123"
    }

    test("response echoes the correlation ID in X-Correlation-ID header") {
        testApplication {
            application {
                install(CorrelationIdHttpServerPlugin)
                routing {
                    get("/ping") { call.respond(HttpStatusCode.OK, "pong") }
                }
            }
            val response = client.get("/ping") {
                header("X-Correlation-ID", "echo-me-456")
            }
            response.headers["X-Correlation-ID"] shouldBe "echo-me-456"
        }
    }

    test("response includes a generated X-Correlation-ID header when none was supplied") {
        testApplication {
            application {
                install(CorrelationIdHttpServerPlugin)
                routing {
                    get("/ping") { call.respond(HttpStatusCode.OK, "pong") }
                }
            }
            val response = client.get("/ping")
            val echoed = response.headers["X-Correlation-ID"]
            echoed shouldNotBe null
            echoed!!.isNotBlank() shouldBe true
        }
    }
})
