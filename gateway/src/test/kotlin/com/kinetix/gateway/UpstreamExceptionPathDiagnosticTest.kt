package com.kinetix.gateway

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 0 diagnostic: pins the actual exception path and gateway response status
 * for 5 endpoints that were believed to return HTTP 500 instead of 404/503.
 *
 * Each test stubs the upstream with either HTTP 404 or HTTP 500 and records
 * what the gateway emits.  This test does NOT fix any behaviour — it captures
 * the current behaviour so future phases can make targeted changes against a
 * known baseline.
 *
 * Architect-review correction verified here: Application.kt:170 installs an
 * explicit `exception<UpstreamErrorException>` handler that reflects
 * `cause.statusCode` back to the caller.  All 5 client methods either:
 *   (a) check 404 explicitly and return null (which the route maps to 404), OR
 *   (b) call handleErrorResponse() which throws UpstreamErrorException(404),
 *       caught by StatusPages and reflected as 404.
 * Therefore upstream-404 already produces gateway-404, NOT 500.
 *
 * The upstream-500 case: handleErrorResponse throws UpstreamErrorException(500),
 * also caught by the explicit handler and reflected as 500.  This is correct —
 * it is the upstream's 500, not a gateway bug.  The fix that IS needed is on
 * getChartData (non-nullable return type with no 404 short-circuit), but even
 * there the UpstreamErrorException path is handled; the residual risk is a
 * deserialization failure when upstream returns 200 with malformed JSON.
 */
class UpstreamExceptionPathDiagnosticTest : FunSpec({

    // ---------------------------------------------------------------------------
    // 1. GET /api/v1/risk/krd/{bookId}
    //    client: getKeyRateDurations — checks NotFound → null; route maps null → 404
    // ---------------------------------------------------------------------------

    test("GET /api/v1/risk/krd/{bookId} — upstream returns 404 — gateway returns 404") {
        val backend = BackendStubServer {
            get("/api/v1/risk/krd/book-1") {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/krd/book-1")
                // FINDING: gateway correctly returns 404 — the client returns null
                // and the route handler maps null to NotFound.
                // No exception reaches the Throwable catch-all.
                response.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/risk/krd/{bookId} — upstream returns 500 — gateway reflects 500 via UpstreamErrorException handler") {
        val backend = BackendStubServer {
            get("/api/v1/risk/krd/book-1") {
                call.respondText(
                    """{"code":"upstream_error","message":"risk-orchestrator blew up"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/krd/book-1")
                // FINDING: getKeyRateDurations only short-circuits on NotFound.
                // For 500 the client calls handleErrorResponse which throws
                // UpstreamErrorException(500). Application.kt:170 catches it and
                // reflects statusCode=500. The route handler never executes.
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["error"]?.jsonPrimitive?.content shouldBe "upstream_error"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    // ---------------------------------------------------------------------------
    // 2. GET /api/v1/books/{bookId}/factor-risk/latest
    //    client: getLatestFactorRisk — checks NotFound → null; route maps null → 404
    // ---------------------------------------------------------------------------

    test("GET /api/v1/books/{bookId}/factor-risk/latest — upstream returns 404 — gateway returns 404") {
        val backend = BackendStubServer {
            get("/api/v1/books/book-1/factor-risk/latest") {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/books/book-1/factor-risk/latest")
                // FINDING: client returns null; route returns 404. No exception path.
                response.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/books/{bookId}/factor-risk/latest — upstream returns 500 — gateway reflects 500 via UpstreamErrorException handler") {
        val backend = BackendStubServer {
            get("/api/v1/books/book-1/factor-risk/latest") {
                call.respondText(
                    """{"code":"upstream_error","message":"factor model unavailable"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/books/book-1/factor-risk/latest")
                // FINDING: UpstreamErrorException(500) thrown by handleErrorResponse,
                // caught at Application.kt:170, statusCode reflected.
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["error"]?.jsonPrimitive?.content shouldBe "upstream_error"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    // ---------------------------------------------------------------------------
    // 3. GET /api/v1/books/{bookId}/margin
    //    client: getMarginEstimate — checks NotFound → null; route maps null → 404
    // ---------------------------------------------------------------------------

    test("GET /api/v1/books/{bookId}/margin — upstream returns 404 — gateway returns 404") {
        val backend = BackendStubServer {
            get("/api/v1/books/book-1/margin") {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/books/book-1/margin")
                // FINDING: client returns null; route returns 404. No exception path.
                response.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/books/{bookId}/margin — upstream returns 500 — gateway reflects 500 via UpstreamErrorException handler") {
        val backend = BackendStubServer {
            get("/api/v1/books/book-1/margin") {
                call.respondText(
                    """{"code":"upstream_error","message":"margin calc failed"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/books/book-1/margin")
                // FINDING: UpstreamErrorException(500) thrown, caught, reflected.
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["error"]?.jsonPrimitive?.content shouldBe "upstream_error"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    // ---------------------------------------------------------------------------
    // 4. GET /api/v1/risk/jobs/{bookId}/chart
    //    client: getChartData — NON-NULLABLE return, no NotFound short-circuit.
    //    Upstream 404 → handleErrorResponse → UpstreamErrorException(404).
    //    StatusPages at Application.kt:170 catches it and reflects statusCode=404.
    //    The hypothesis that response.body<ChartDataClientDto>() could cause an
    //    NPE/deserialization exception is incorrect: handleErrorResponse() throws
    //    before body() is ever called.
    // ---------------------------------------------------------------------------

    test("GET /api/v1/risk/jobs/{bookId}/chart — upstream returns 404 — gateway reflects 404 via UpstreamErrorException handler (not Throwable catch-all)") {
        val from = "2025-01-01T00:00:00Z"
        val to = "2025-01-02T00:00:00Z"
        val backend = BackendStubServer {
            get("/api/v1/risk/jobs/book-1/chart") {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/jobs/book-1/chart?from=$from&to=$to")
                // FINDING: getChartData has no NotFound → null short-circuit.
                // Instead: handleErrorResponse throws UpstreamErrorException(404).
                // Application.kt:170 catches it and reflects 404.
                // response.body<ChartDataClientDto>() is NEVER called — no NPE risk.
                // The "deserialization NPE hypothesis" in the issue is disproved.
                response.status shouldBe HttpStatusCode.NotFound
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["error"]?.jsonPrimitive?.content shouldBe "upstream_error"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/risk/jobs/{bookId}/chart — upstream returns 500 — gateway reflects 500 via UpstreamErrorException handler") {
        val from = "2025-01-01T00:00:00Z"
        val to = "2025-01-02T00:00:00Z"
        val backend = BackendStubServer {
            get("/api/v1/risk/jobs/book-1/chart") {
                call.respondText(
                    """{"code":"upstream_error","message":"chart aggregation timeout"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/jobs/book-1/chart?from=$from&to=$to")
                // FINDING: UpstreamErrorException(500) thrown, caught, reflected.
                // This is correct behaviour — upstream 500 surfaces as gateway 500.
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["error"]?.jsonPrimitive?.content shouldBe "upstream_error"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    // ---------------------------------------------------------------------------
    // 5. GET /api/v1/risk/pnl-attribution/{bookId}
    //    client: getPnlAttribution — checks NotFound → null; route maps null → 404
    // ---------------------------------------------------------------------------

    test("GET /api/v1/risk/pnl-attribution/{bookId} — upstream returns 404 — gateway returns 404") {
        val backend = BackendStubServer {
            get("/api/v1/risk/pnl-attribution/book-1") {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/pnl-attribution/book-1")
                // FINDING: client returns null; route returns 404. No exception path.
                response.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/risk/pnl-attribution/{bookId} — upstream returns 500 — gateway reflects 500 via UpstreamErrorException handler") {
        val backend = BackendStubServer {
            get("/api/v1/risk/pnl-attribution/book-1") {
                call.respondText(
                    """{"code":"upstream_error","message":"P&L attribution engine unavailable"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/pnl-attribution/book-1")
                // FINDING: UpstreamErrorException(500) thrown, caught, reflected.
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["error"]?.jsonPrimitive?.content shouldBe "upstream_error"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
