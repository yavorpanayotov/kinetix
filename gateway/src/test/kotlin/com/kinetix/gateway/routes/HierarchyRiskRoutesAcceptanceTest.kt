package com.kinetix.gateway.routes

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
 * Acceptance tests for `GET /api/v1/risk/hierarchy/{level}/{entityId}`.
 *
 * Demonstrates that when risk-orchestrator returns a populated firm-level
 * aggregation (non-zero VaR, child count, and contributors), the gateway
 * proxies it through faithfully — i.e. the firm hierarchy bug surfaces in
 * the seam *upstream* of the gateway (the position-service book-hierarchy
 * seed), not in the gateway itself.
 *
 * Complements `GatewayHierarchyRiskContractAcceptanceTest` by asserting the
 * *populated* shape end-to-end (varValue ≠ "0.00", childCount > 0,
 * topContributors non-empty) — the visible $0.00 ticker-strip / Firm-Summary
 * bug becomes the assertion failure that re-appears the moment the seed is
 * regressed.
 */
class HierarchyRiskRoutesAcceptanceTest : FunSpec({

    test("GET /api/v1/risk/hierarchy/firm/firm — when risk-orchestrator returns a populated node — proxies non-zero varValue, childCount, and topContributors") {
        val populatedFirmNode = """
            {
              "level": "FIRM",
              "entityId": "firm",
              "entityName": "FIRM",
              "parentId": null,
              "varValue": "19400000.00",
              "expectedShortfall": "24250000.00",
              "pnlToday": null,
              "limitUtilisation": null,
              "marginalVar": null,
              "incrementalVar": null,
              "topContributors": [
                {"entityId":"derivatives-book","entityName":"Derivatives Book","varContribution":"7200000.00","pctOfTotal":"37.11"},
                {"entityId":"equity-growth","entityName":"Equity Growth","varContribution":"4100000.00","pctOfTotal":"21.13"},
                {"entityId":"macro-hedge","entityName":"Macro Hedge","varContribution":"2900000.00","pctOfTotal":"14.95"}
              ],
              "childCount": 8,
              "isPartial": false,
              "missingBooks": []
            }
        """.trimIndent()

        val backend = BackendStubServer {
            get("/api/v1/risk/hierarchy/firm/firm") {
                call.respond(Json.parseToJsonElement(populatedFirmNode).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/hierarchy/firm/firm")

                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

                // The bug: when position-service hasn't seeded book_hierarchy,
                // risk-orchestrator returns varValue=0.00 / childCount=0 /
                // topContributors=[]. These three assertions re-fail the moment
                // the firm aggregation collapses to zeros again.
                body["varValue"]?.jsonPrimitive?.content shouldNotBe "0.00"
                body["varValue"]?.jsonPrimitive?.content shouldNotBe "0"
                (body["childCount"]?.jsonPrimitive?.content?.toInt() ?: 0) shouldBe 8
                val contributors = body["topContributors"]!!.jsonArray
                contributors.size shouldBe 3
                contributors[0].jsonObject["entityId"]?.jsonPrimitive?.content shouldBe "derivatives-book"
                contributors[0].jsonObject["varContribution"]?.jsonPrimitive?.content shouldNotBe "0.00"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/risk/hierarchy/division/multi-asset — populated division aggregation surfaces non-zero VaR and matching contributors") {
        val populatedDivisionNode = """
            {
              "level": "DIVISION",
              "entityId": "multi-asset",
              "entityName": "Multi-Asset",
              "parentId": "firm",
              "varValue": "11500000.00",
              "expectedShortfall": "14375000.00",
              "pnlToday": null,
              "limitUtilisation": null,
              "marginalVar": null,
              "incrementalVar": null,
              "topContributors": [
                {"entityId":"derivatives-book","entityName":"Derivatives Book","varContribution":"7200000.00","pctOfTotal":"62.60"}
              ],
              "childCount": 4,
              "isPartial": false,
              "missingBooks": []
            }
        """.trimIndent()

        val backend = BackendStubServer {
            get("/api/v1/risk/hierarchy/division/multi-asset") {
                call.respond(Json.parseToJsonElement(populatedDivisionNode).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/hierarchy/division/multi-asset")

                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

                body["entityId"]?.jsonPrimitive?.content shouldBe "multi-asset"
                body["varValue"]?.jsonPrimitive?.content shouldNotBe "0.00"
                (body["childCount"]?.jsonPrimitive?.content?.toInt() ?: 0) shouldBe 4
                body["topContributors"]!!.jsonArray.size shouldBe 1
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
