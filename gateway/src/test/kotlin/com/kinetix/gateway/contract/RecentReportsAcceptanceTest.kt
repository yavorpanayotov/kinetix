package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Trader-review P2 #24 — Reports tab currently has no history. A trader
 * runs `Generate Report` and has no way to see what was generated, by
 * whom, when, or whether it's still running. This pins the gateway
 * `GET /api/v1/reports/recent` surface so the UI can render a
 * "Recent Reports" panel with timestamp / user / status / download link.
 *
 * The endpoint proxies through to the orchestrator's
 * `GET /api/v1/reports/recent?limit=N` and returns rows in
 * reverse-chronological order. Each row carries:
 *   - outputId      (download identifier)
 *   - templateId    (which report ran)
 *   - timestamp     (ISO-8601 generated-at)
 *   - user          (requestedBy — who triggered it; "SYSTEM" for scheduled)
 *   - status        (RUNNING | COMPLETE | FAILED)
 *   - downloadUrl   (relative path the UI can hit for the CSV)
 *   - rowCount      (so the row can show "42 rows")
 */
class RecentReportsAcceptanceTest : FunSpec({

    val recentReportsJson = """
        [
          {
            "outputId":"out-3",
            "templateId":"tpl-risk-summary",
            "timestamp":"2026-05-28T10:30:00Z",
            "user":"trader1",
            "status":"COMPLETE",
            "downloadUrl":"/api/v1/reports/out-3/csv",
            "rowCount":42
          },
          {
            "outputId":"out-2",
            "templateId":"tpl-stress-summary",
            "timestamp":"2026-05-28T09:15:00Z",
            "user":"trader2",
            "status":"RUNNING",
            "downloadUrl":"/api/v1/reports/out-2/csv",
            "rowCount":0
          },
          {
            "outputId":"out-1",
            "templateId":"tpl-pnl-attribution",
            "timestamp":"2026-05-27T17:00:00Z",
            "user":"riskops",
            "status":"FAILED",
            "downloadUrl":"/api/v1/reports/out-1/csv",
            "rowCount":0
          }
        ]
    """.trimIndent()

    test("GET /api/v1/reports/recent returns 200 with reverse-chronological list and required fields") {
        val backend = BackendStubServer {
            get("/api/v1/reports/recent") {
                call.respond(Json.parseToJsonElement(recentReportsJson).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/reports/recent")

                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                body.size shouldBe 3

                // Reverse-chronological — newest first.
                body[0].jsonObject["outputId"]?.jsonPrimitive?.content shouldBe "out-3"
                body[1].jsonObject["outputId"]?.jsonPrimitive?.content shouldBe "out-2"
                body[2].jsonObject["outputId"]?.jsonPrimitive?.content shouldBe "out-1"

                // Every row carries the contract fields the UI panel needs.
                val first = body[0].jsonObject
                first["timestamp"]?.jsonPrimitive?.content shouldBe "2026-05-28T10:30:00Z"
                first["user"]?.jsonPrimitive?.content shouldBe "trader1"
                first["status"]?.jsonPrimitive?.content shouldBe "COMPLETE"
                first["downloadUrl"]?.jsonPrimitive?.content shouldBe "/api/v1/reports/out-3/csv"
                first["templateId"]?.jsonPrimitive?.content shouldBe "tpl-risk-summary"
                first["rowCount"]?.jsonPrimitive?.content shouldBe "42"

                // Mixed status values are surfaced unmodified.
                body[1].jsonObject["status"]?.jsonPrimitive?.content shouldBe "RUNNING"
                body[2].jsonObject["status"]?.jsonPrimitive?.content shouldBe "FAILED"

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/reports/recent" }
                recorded.method shouldBe "GET"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/reports/recent?limit=5 forwards the limit query parameter to the upstream") {
        val backend = BackendStubServer {
            get("/api/v1/reports/recent") {
                call.respond(Json.parseToJsonElement("[]").jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/reports/recent?limit=5")

                response.status shouldBe HttpStatusCode.OK

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/reports/recent" }
                recorded.query["limit"]?.firstOrNull() shouldBe "5"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/reports/recent returns an empty array when no reports exist") {
        val backend = BackendStubServer {
            get("/api/v1/reports/recent") {
                call.respond(Json.parseToJsonElement("[]").jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/reports/recent")

                response.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(response.bodyAsText()).jsonArray.size shouldBe 0
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
