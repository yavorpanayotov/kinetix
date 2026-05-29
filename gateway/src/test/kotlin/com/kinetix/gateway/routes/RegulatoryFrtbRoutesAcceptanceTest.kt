package com.kinetix.gateway.routes

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acceptance tests for `POST /api/v1/regulatory/frtb/{bookId}` that pin the
 * routing contract: the path captures a *book identifier*, not a verb.
 *
 * The bug we're locking down (ui-fix-v1 G2): posting to
 * `/api/v1/regulatory/frtb/calculate` captured the literal string
 * "calculate" as `{bookId}`, returned 200, and emitted a useless all-zero
 * FRTB result — a client foot-gun and a stale echo of an earlier
 * `/frtb/calculate` verb-style API. Both halves of the contract are pinned
 * here: a real book id still returns 200 with the canonical
 * `FrtbResultResponse` shape, and a reserved path word like "calculate"
 * is rejected as a 400 client error so future regressions don't silently
 * surface as zero-valued capital charges.
 */
class RegulatoryFrtbRoutesAcceptanceTest : FunSpec({

    val frtbResultJson = """
        {
          "bookId":"balanced-income",
          "sbmCharges":[
            {"riskClass":"EQUITY","deltaCharge":"40000.00","vegaCharge":"30000.00","curvatureCharge":"4000.00","totalCharge":"74000.00"},
            {"riskClass":"GIRR","deltaCharge":"1500.00","vegaCharge":"1000.00","curvatureCharge":"112.50","totalCharge":"2612.50"}
          ],
          "totalSbmCharge":"76612.50",
          "grossJtd":"5400.00",
          "hedgeBenefit":"0.00",
          "netDrc":"5400.00",
          "exoticNotional":"400000.00",
          "otherNotional":"1700000.00",
          "totalRrao":"5700.00",
          "totalCapitalCharge":"87712.50",
          "calculatedAt":"2026-05-19T12:00:00Z"
        }
    """.trimIndent()

    test("POST /api/v1/regulatory/frtb/{bookId} with a real book id returns 200 with the canonical FRTB charges") {
        val backend = BackendStubServer {
            post("/api/v1/regulatory/frtb/balanced-income") {
                call.respond(Json.parseToJsonElement(frtbResultJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/regulatory/frtb/balanced-income") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["bookId"]?.jsonPrimitive?.content shouldBe "balanced-income"
                body["totalCapitalCharge"]?.jsonPrimitive?.content shouldBe "87712.50"
                body["totalSbmCharge"]?.jsonPrimitive?.content shouldBe "76612.50"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/regulatory/frtb/{bookId}/latest returns 200 with the most recent persisted FRTB calculation so the tab can show it by default") {
        val backend = BackendStubServer {
            get("/api/v1/regulatory/frtb/balanced-income/latest") {
                call.respond(Json.parseToJsonElement(frtbResultJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/regulatory/frtb/balanced-income/latest")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["bookId"]?.jsonPrimitive?.content shouldBe "balanced-income"
                body["totalCapitalCharge"]?.jsonPrimitive?.content shouldBe "87712.50"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/regulatory/frtb/{bookId}/latest returns 404 when the book has no calculation so the tab falls back to the empty state") {
        val backend = BackendStubServer {
            get("/api/v1/regulatory/frtb/never-calculated/latest") {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/regulatory/frtb/never-calculated/latest")
                response.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/regulatory/frtb/calculate is rejected as 400 — 'calculate' is a reserved path word, not a book id") {
        // Locks down ui-fix-v1 G2: the literal string "calculate" must
        // never be captured as `{bookId}`. The gateway must refuse the
        // request locally — the downstream regulatory-service must not
        // be called.
        val backend = BackendStubServer {
            // No FRTB route registered — any forwarded call would
            // record a request that we then assert against.
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/regulatory/frtb/calculate") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                response.status shouldBe HttpStatusCode.BadRequest
                backend.recordedRequests.isEmpty() shouldBe true
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
