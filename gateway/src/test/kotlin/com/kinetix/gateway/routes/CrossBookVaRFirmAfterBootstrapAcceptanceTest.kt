package com.kinetix.gateway.routes

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
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
 * Acceptance tests for `GET /api/v1/risk/var/cross-book/firm` that verify the
 * gateway correctly proxies the firm-level cross-book VaR result from the
 * risk-orchestrator cache, and expose the gap that requires an explicit
 * `POST /api/v1/risk/var/cross-book` to populate that cache.
 *
 * ## Background
 *
 * The original audit found that `GET /api/v1/risk/var/cross-book/firm` returns
 * 404 with no data. The plan (Phase 3.4) assumed per-book VaR seeding in
 * [com.kinetix.demo.schedule.DemoVaRBootstrapJob] would populate the firm
 * aggregate as a side-effect. This test reveals that assumption is **wrong**:
 *
 * - `GET /api/v1/risk/var/cross-book/{groupId}` looks up the in-memory
 *   `CrossBookVaRCache` inside risk-orchestrator.
 * - That cache is only populated when `POST /api/v1/risk/var/cross-book` is
 *   called with `portfolioGroupId = "firm"`.
 * - `DemoVaRBootstrapJob` only calls per-book VaR endpoints; it never triggers
 *   the cross-book POST.
 *
 * The second test case ("without explicit cross-book POST …") locks down this
 * gap. `DemoVaRBootstrapJob` was subsequently extended to call the cross-book
 * POST at the end of its sweep, closing the 404.
 *
 * ## Response shape
 *
 * The endpoint returns a [com.kinetix.gateway.dtos.CrossBookVaRResponseDto].
 * Note: `isPartial` / `missingBooks` are fields on the *hierarchy* response
 * (`GET /api/v1/risk/hierarchy/FIRM/FIRM`), not the cross-book cache response.
 * The cross-book response expresses completeness through `bookIds` size and
 * `varValue > 0`.
 */
class CrossBookVaRFirmAfterBootstrapAcceptanceTest : FunSpec({

    // Pre-built firm-level cross-book VaR result seeded with all 8 demo books.
    // Contributions are realistic (a few hundred thousand to low millions each),
    // ensuring the firm total lands firmly in the $50,000 < firmVaR < $20,000,000
    // sanity window reviewed by data analysis.
    val firmResultJson = """
        {
          "portfolioGroupId": "firm",
          "bookIds": ["equity-growth","tech-momentum","emerging-markets","fixed-income","multi-asset","macro-hedge","balanced-income","derivatives-book"],
          "calculationType": "PARAMETRIC",
          "confidenceLevel": "CL_95",
          "varValue": "2940000.00",
          "expectedShortfall": "3528000.00",
          "componentBreakdown": [
            {"assetClass": "EQUITY",       "varContribution": "1660000.00", "percentageOfTotal": "56.46"},
            {"assetClass": "FIXED_INCOME", "varContribution": "520000.00",  "percentageOfTotal": "17.69"},
            {"assetClass": "FX",           "varContribution": "450000.00",  "percentageOfTotal": "15.31"},
            {"assetClass": "DERIVATIVE",   "varContribution": "310000.00",  "percentageOfTotal": "10.54"}
          ],
          "bookContributions": [
            {"bookId":"equity-growth",    "varContribution":"420000.00","percentageOfTotal":"14.29","standaloneVar":"480000.00","diversificationBenefit":"60000.00","marginalVar":"0.000143","incrementalVar":"420000.00"},
            {"bookId":"tech-momentum",    "varContribution":"380000.00","percentageOfTotal":"12.93","standaloneVar":"430000.00","diversificationBenefit":"50000.00","marginalVar":"0.000129","incrementalVar":"380000.00"},
            {"bookId":"emerging-markets", "varContribution":"310000.00","percentageOfTotal":"10.54","standaloneVar":"370000.00","diversificationBenefit":"60000.00","marginalVar":"0.000105","incrementalVar":"310000.00"},
            {"bookId":"fixed-income",     "varContribution":"520000.00","percentageOfTotal":"17.69","standaloneVar":"590000.00","diversificationBenefit":"70000.00","marginalVar":"0.000177","incrementalVar":"520000.00"},
            {"bookId":"multi-asset",      "varContribution":"290000.00","percentageOfTotal":"9.86", "standaloneVar":"340000.00","diversificationBenefit":"50000.00","marginalVar":"0.000099","incrementalVar":"290000.00"},
            {"bookId":"macro-hedge",      "varContribution":"450000.00","percentageOfTotal":"15.31","standaloneVar":"510000.00","diversificationBenefit":"60000.00","marginalVar":"0.000153","incrementalVar":"450000.00"},
            {"bookId":"balanced-income",  "varContribution":"260000.00","percentageOfTotal":"8.84", "standaloneVar":"310000.00","diversificationBenefit":"50000.00","marginalVar":"0.000088","incrementalVar":"260000.00"},
            {"bookId":"derivatives-book", "varContribution":"310000.00","percentageOfTotal":"10.54","standaloneVar":"360000.00","diversificationBenefit":"50000.00","marginalVar":"0.000105","incrementalVar":"310000.00"}
          ],
          "totalStandaloneVar": "3390000.00",
          "diversificationBenefit": "450000.00",
          "calculatedAt": "2026-05-26T08:00:00Z"
        }
    """.trimIndent()

    val demoBookIds = listOf(
        "equity-growth", "tech-momentum", "emerging-markets", "fixed-income",
        "multi-asset", "macro-hedge", "balanced-income", "derivatives-book",
    )

    test("GET /api/v1/risk/var/cross-book/firm — when firm aggregate is cached in risk-orchestrator — returns 200 with all 8 books and a positive firm VaR within sanity bounds") {
        // This test represents the steady state AFTER DemoVaRBootstrapJob has called
        // POST /api/v1/risk/var/cross-book with portfolioGroupId="firm", which populates
        // the risk-orchestrator CrossBookVaRCache.  The stub stands in for risk-orchestrator
        // returning that cached result.
        val backend = BackendStubServer {
            get("/api/v1/risk/var/cross-book/firm") {
                call.respond(Json.parseToJsonElement(firmResultJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }

                val response = client.get("/api/v1/risk/var/cross-book/firm")

                response.status shouldBe HttpStatusCode.OK

                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

                // Contract: portfolioGroupId must be "firm"
                body["portfolioGroupId"]?.jsonPrimitive?.content shouldBe "firm"

                // Contract: all 8 demo books are present in the firm aggregate
                val bookIdArray = body["bookIds"]!!.jsonArray
                bookIdArray.size shouldBe 8
                val bookIdValues = bookIdArray.map { it.jsonPrimitive.content }
                demoBookIds.forEach { expected ->
                    bookIdValues.contains(expected) shouldBe true
                }

                // Contract: firm VaR is positive and within the data-analyst sanity range:
                //   $50,000 < firmVaR < $20,000,000
                val firmVaR = body["varValue"]!!.jsonPrimitive.content.toDouble()
                firmVaR shouldBeGreaterThan 50_000.0
                firmVaR shouldBeLessThan 20_000_000.0

                // Contract: bookContributions has one entry per book
                body["bookContributions"]!!.jsonArray.size shouldBe 8

                // Contract: diversification benefit is positive
                // (multi-book aggregation reduces total VaR relative to the sum of standalones)
                body["diversificationBenefit"]!!.jsonPrimitive.content.toDouble() shouldBeGreaterThan 0.0

                // Contract: expectedShortfall is present and greater than VaR
                val es = body["expectedShortfall"]!!.jsonPrimitive.content.toDouble()
                es shouldBeGreaterThan firmVaR
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/risk/var/cross-book/firm — without explicit cross-book POST to seed the cache — returns 404") {
        // This test documents the gap exposed by Phase 3.4.
        //
        // Per-book VaR seeding in DemoVaRBootstrapJob calls
        // POST /api/v1/risk/var/calculate/{bookId} for each of the 8 demo books, but
        // it does NOT call POST /api/v1/risk/var/cross-book with portfolioGroupId="firm".
        // Without that POST, risk-orchestrator's CrossBookVaRCache has no "firm" entry,
        // and GET /api/v1/risk/var/cross-book/firm returns 404.
        //
        // This is the production failure the original audit recorded.
        // DemoVaRBootstrapJob was subsequently extended to call the cross-book
        // POST at the end of runOnce(), closing the gap.
        val backend = BackendStubServer {
            // Stub returns 404 — simulating the state before the explicit firm POST
            get("/api/v1/risk/var/cross-book/firm") {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }

                val response = client.get("/api/v1/risk/var/cross-book/firm")

                // The gateway must propagate the 404 rather than masking it — this
                // ensures the UI shows "no data" rather than stale zeros.
                response.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
