package com.kinetix.gateway

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acceptance tests for the gateway's StatusPages plugin (the global error
 * handler installed in `Application.module()`).
 *
 * The contract these tests pin: when a client posts a request body that
 * fails JSON deserialization (wrong field type, missing required field, or
 * outright malformed JSON), the gateway returns 400 with a descriptive
 * message — *not* 500 `internal_error`. Today the handler only special-cases
 * `IllegalArgumentException`; every kotlinx.serialization or Ktor
 * content-negotiation throwable falls through to the `Throwable` catch-all
 * and surfaces as a 500, which causes audits to wrongly diagnose the
 * gateway as broken when the real fault is a malformed caller payload.
 *
 * See `plans/ui-fix-v1.md` checkbox 2.4.
 */
class StatusPagesAcceptanceTest : FunSpec({

    test("POST /api/v1/risk/var/{bookId} with a numeric confidenceLevel — returns 400 mentioning confidenceLevel, not 500") {
        // confidenceLevel is declared as String? on VaRCalculationRequest.
        // Sending a JSON number triggers kotlinx.serialization to throw a
        // JsonConvertException whose message names the offending field.
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/risk/var/balanced-income") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"confidenceLevel": 0.95}""")
                }

                response.status shouldBe HttpStatusCode.BadRequest
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["error"]?.jsonPrimitive?.content shouldBe "invalid_request_body"
                val message = body["message"]?.jsonPrimitive?.content ?: ""
                message shouldContain "confidenceLevel"
                // The upstream backend must not have been called — gateway
                // failed at the deserialization seam before forwarding.
                backend.recordedRequests.isEmpty() shouldBe true
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/risk/var/cross-book missing the required portfolioGroupId — returns 400 mentioning portfolioGroupId, not 500") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/risk/var/cross-book") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookIds":["a","b"]}""")
                }

                response.status shouldBe HttpStatusCode.BadRequest
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["error"]?.jsonPrimitive?.content shouldBe "invalid_request_body"
                val message = body["message"]?.jsonPrimitive?.content ?: ""
                message shouldContain "portfolioGroupId"
                backend.recordedRequests.isEmpty() shouldBe true
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/risk/var/cross-book with entirely malformed JSON — returns 400, not 500") {
        // Raw non-JSON body. Ktor's content-negotiation surfaces this as a
        // JsonConvertException (wrapping a kotlinx.serialization throwable),
        // which the new handler flattens to 400. Without the fix this lands
        // in the Throwable catch-all and returns 500.
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/risk/var/cross-book") {
                    contentType(ContentType.Application.Json)
                    setBody("not-json")
                }

                response.status shouldBe HttpStatusCode.BadRequest
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                // Either invalid_request_body (serialization-layer throw) or
                // bad_request (Ktor BadRequestException) is acceptable — the
                // contract is "a client error, with a descriptive message,
                // and the upstream backend is not called."
                val errorCode = body["error"]?.jsonPrimitive?.content ?: ""
                (errorCode == "invalid_request_body" || errorCode == "bad_request") shouldBe true
                backend.recordedRequests.isEmpty() shouldBe true
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
