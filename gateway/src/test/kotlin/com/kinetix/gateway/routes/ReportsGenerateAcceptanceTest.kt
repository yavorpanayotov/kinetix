package com.kinetix.gateway.routes

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * End-to-end acceptance for the Reports proxy on `POST /api/v1/reports/generate`.
 *
 * Plan §4.2 — the live deploy was returning HTTP 500
 *   `{"error":"upstream_error","message":"Report generation failed"}`
 * when the UI sent its actual shape `{templateId,bookId}`. The 500 originated
 * inside the risk-orchestrator's `JdbcReportQueryExecutor`, which threw on:
 *   - SQLState 55000 — `risk_positions_flat` materialised view unpopulated
 *   - SQLState 42P01 — `stress_test_results` table not in this DB
 *
 * The fix lives in the executor (see `JdbcReportQueryExecutorSqlExceptionTest`):
 * those two known degraded-environment SQL states become an empty result, and
 * the route serves a normal 200 `ReportOutput` with `rowCount: 0`.
 *
 * These tests pin the gateway side of that contract: the gateway must
 *   1. forward the UI's exact payload shape to the upstream report endpoint,
 *   2. surface a 200 `ReportResponse` JSON when the upstream succeeds — including
 *      the degraded "0 rows" case after the executor fix,
 *   3. continue to relay upstream errors faithfully (no over-eager 200s) so the
 *      UI toast wired up in §4.3 has a real signal to render.
 */
class ReportsGenerateAcceptanceTest : FunSpec({

    val populatedOutputJson = """
        {
          "outputId":"out-123",
          "templateId":"tpl-risk-summary",
          "generatedAt":"2026-05-19T10:00:00Z",
          "outputFormat":"JSON",
          "rowCount":42
        }
    """.trimIndent()

    val emptyOutputJson = """
        {
          "outputId":"out-empty",
          "templateId":"tpl-risk-summary",
          "generatedAt":"2026-05-19T10:00:00Z",
          "outputFormat":"JSON",
          "rowCount":0
        }
    """.trimIndent()

    test("POST /api/v1/reports/generate with the UI-shape payload returns 200 with ReportResponse fields") {
        val backend = BackendStubServer {
            post("/api/v1/reports/generate") {
                call.respond(Json.parseToJsonElement(populatedOutputJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }

                // This is the EXACT payload shape the UI sends from
                // ui/src/api/reports.ts:generateReport — templateId + bookId
                // with no explicit format. Live deploy returned 500 here.
                val response = client.post("/api/v1/reports/generate") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"templateId":"tpl-risk-summary","bookId":"balanced-income"}""")
                }

                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["outputId"]?.jsonPrimitive?.content shouldBe "out-123"
                body["templateId"]?.jsonPrimitive?.content shouldBe "tpl-risk-summary"
                body["outputFormat"]?.jsonPrimitive?.content shouldBe "JSON"
                body["rowCount"]?.jsonPrimitive?.content shouldBe "42"
                body["generatedAt"]?.jsonPrimitive?.content shouldNotBe null

                // The gateway must forward the request body untouched so the
                // upstream sees the same templateId/bookId pair.
                val recorded = backend.recordedRequests.single { it.path == "/api/v1/reports/generate" }
                recorded.method shouldBe "POST"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/reports/generate returns 200 with rowCount:0 when upstream has no data (degraded mat view / cross-DB table)") {
        // After the JdbcReportQueryExecutor fix, the two SQL-degradation cases
        // (mat view not populated; table not in this DB) become a successful
        // 0-row response. The gateway must propagate that as a normal 200 so
        // the UI renders "0 rows" rather than a toast.
        val backend = BackendStubServer {
            post("/api/v1/reports/generate") {
                call.respond(Json.parseToJsonElement(emptyOutputJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/reports/generate") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"templateId":"tpl-risk-summary","bookId":"balanced-income"}""")
                }

                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["rowCount"]?.jsonPrimitive?.content shouldBe "0"
                body["outputId"]?.jsonPrimitive?.content shouldBe "out-empty"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/reports/generate surfaces upstream 500 as an upstream_error so the UI toast has a real message to render") {
        // Defensive: even with the executor hardening, the gateway must keep
        // honestly forwarding any *non-degraded* upstream failures so the
        // §4.3 toast wiring is meaningful. The body shape is the gateway's
        // canonical `{code,message,correlationId}` ApiError.
        val backend = BackendStubServer {
            post("/api/v1/reports/generate") {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    Json.parseToJsonElement(
                        """{"error":"internal_error","message":"Report generation failed"}"""
                    ).jsonObject,
                )
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/reports/generate") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"templateId":"tpl-risk-summary","bookId":"balanced-income"}""")
                }

                response.status shouldBe HttpStatusCode.InternalServerError
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["code"]?.jsonPrimitive?.content shouldBe "UPSTREAM_ERROR"
                // The upstream message MUST flow through so the UI toast in
                // §4.3 can render it verbatim.
                body["message"]?.jsonPrimitive?.content shouldBe "Report generation failed"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
