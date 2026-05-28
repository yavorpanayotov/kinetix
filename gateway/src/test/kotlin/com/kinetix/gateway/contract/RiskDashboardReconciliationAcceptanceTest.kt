package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.math.abs

/**
 * Trader-review P0 #5 regression: `Risk → Dashboard` rendered FOUR different
 * "total VaR" numbers for the same scope on the same page.
 *
 *  - Header VaR (latest single-book / cross-book run) was `$190.1K`.
 *  - "Sum of books" caption was `$13.4M`.
 *  - Book Contributions table summed to roughly `$182M`.
 *  - Factor Risk Decomposition reported `Total VaR $1K` for the same scope.
 *
 * The plan (`plans/ui-trader-review.md`, P0 #5) picks the header value as
 * canonical and requires all three other surfaces to derive from the SAME
 * promoted run, SAME scope, SAME units, reconciling within rounding.
 *
 * The reconciliation invariants — straight out of `specs/risk.allium`
 * (`CrossBookValuationResult`, the `ProcessCrossBookVaRRequested` guidance,
 * and `DiversificationBenefitNonNegative`):
 *
 *   1. The cross-book aggregate VaR equals total standalone VaR minus the
 *      diversification benefit:
 *          aggregate_var = total_standalone_var - diversification_benefit
 *   2. The per-book `varContribution` values are a decomposition of the
 *      DIVERSIFIED aggregate VaR, NOT of the undiversified sum-of-standalones,
 *      so `sum(bookContributions[].varContribution) == aggregate_var` within
 *      a rounding epsilon.
 *   3. The factor-decomposition `totalVar` must agree with the same
 *      aggregate VaR for the SAME scope (so a trader can read "Total VaR"
 *      twice on the page and see the same number).
 *
 * Each test wires the gateway against a small set of risk-orchestrator stubs
 * that return a *consistent* fixture, then pulls the three / four surfaces
 * out of the gateway response and asserts they reconcile. Acceptance tests
 * follow the gRPC fake-server pattern from CLAUDE.md ("Project Conventions")
 * — no client / repository / service mocks; everything goes over real HTTP.
 */
class RiskDashboardReconciliationAcceptanceTest : FunSpec({

    // Fixture: aggregate (header) VaR = $1.0M. Three books contribute
    // $0.40M / $0.40M / $0.30M = $1.10M of varContribution → wait, the
    // contributions must SUM to the aggregate. Pick contributions that sum
    // to the aggregate exactly to make the invariant obvious:
    //   bookA varContribution = $0.40M
    //   bookB varContribution = $0.40M
    //   bookC varContribution = $0.20M
    //   sum                   = $1.00M  == aggregate
    //   standalone sum        = $1.20M
    //   diversification benefit = $0.20M  (>= 0 per the invariant)
    //   1.20M - 0.20M = 1.00M  ✓
    //
    // Factor decomposition totalVar matches aggregate: $1.00M.
    val aggregateVar = 1_000_000.00
    val bookAVar = 400_000.00
    val bookBVar = 400_000.00
    val bookCVar = 200_000.00
    val standaloneA = 500_000.00
    val standaloneB = 450_000.00
    val standaloneC = 250_000.00
    val diversificationBenefit = (standaloneA + standaloneB + standaloneC) - aggregateVar
    val tolerance = 1.00  // ±$1 reconciliation epsilon

    val crossBookConsistentJson = """
        {
          "portfolioGroupId":"desk-multi-asset",
          "bookIds":["book-a","book-b","book-c"],
          "calculationType":"PARAMETRIC",
          "confidenceLevel":"CL_95",
          "varValue":$aggregateVar,
          "expectedShortfall":1250000.00,
          "componentBreakdown":[
            {"assetClass":"EQUITY","varContribution":600000.00,"percentageOfTotal":60.0},
            {"assetClass":"FIXED_INCOME","varContribution":400000.00,"percentageOfTotal":40.0}
          ],
          "bookContributions":[
            {"bookId":"book-a","varContribution":$bookAVar,"percentageOfTotal":40.0,"standaloneVar":$standaloneA,"diversificationBenefit":100000.00,"marginalVar":0.000200,"incrementalVar":350000.00},
            {"bookId":"book-b","varContribution":$bookBVar,"percentageOfTotal":40.0,"standaloneVar":$standaloneB,"diversificationBenefit":50000.00,"marginalVar":0.000180,"incrementalVar":300000.00},
            {"bookId":"book-c","varContribution":$bookCVar,"percentageOfTotal":20.0,"standaloneVar":$standaloneC,"diversificationBenefit":50000.00,"marginalVar":0.000090,"incrementalVar":150000.00}
          ],
          "totalStandaloneVar":${standaloneA + standaloneB + standaloneC},
          "diversificationBenefit":$diversificationBenefit,
          "calculatedAt":"2026-05-28T10:25:00Z"
        }
    """.trimIndent()

    val singleBookHeaderJson = """
        {
          "bookId":"desk-multi-asset",
          "calculationType":"PARAMETRIC",
          "confidenceLevel":"CL_95",
          "varValue":"$aggregateVar",
          "expectedShortfall":"1250000.00",
          "componentBreakdown":[
            {"assetClass":"EQUITY","varContribution":"600000.00","percentageOfTotal":"60.00"},
            {"assetClass":"FIXED_INCOME","varContribution":"400000.00","percentageOfTotal":"40.00"}
          ],
          "calculatedAt":"2026-05-28T10:25:00Z",
          "pvValue":"50000000.00"
        }
    """.trimIndent()

    val factorRiskConsistentJson = """
        {
          "bookId":"desk-multi-asset",
          "calculatedAt":"2026-05-28T10:25:00Z",
          "totalVar":$aggregateVar,
          "systematicVar":700000.00,
          "idiosyncraticVar":300000.00,
          "rSquared":0.82,
          "concentrationWarning":false,
          "factors":[
            {"factorType":"EQUITY_MARKET","factorExposure":0.65,"varContribution":500000.00,"pnlAttribution":-25000.00,"pctOfTotal":50.0,"loading":1.2,"loadingMethod":"OLS"},
            {"factorType":"RATES_LEVEL","factorExposure":0.25,"varContribution":200000.00,"pnlAttribution":-10000.00,"pctOfTotal":20.0,"loading":0.8,"loadingMethod":"OLS"}
          ]
        }
    """.trimIndent()

    test("Risk dashboard surfaces reconcile within rounding when orchestrator returns a single consistent promoted run") {
        // Same scope ("desk-multi-asset"), same calculated_at, same VaR
        // number — wired through three different gateway endpoints exactly
        // as the UI assembles the Risk dashboard.
        val backend = BackendStubServer {
            get("/api/v1/risk/var/desk-multi-asset") {
                call.respond(Json.parseToJsonElement(singleBookHeaderJson).jsonObject)
            }
            post("/api/v1/risk/var/cross-book") {
                call.respond(Json.parseToJsonElement(crossBookConsistentJson).jsonObject)
            }
            get("/api/v1/books/desk-multi-asset/factor-risk/latest") {
                call.respond(Json.parseToJsonElement(factorRiskConsistentJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }

                // 1. Header VaR (the live ticker / Market-Risk section header).
                val headerResp = client.get("/api/v1/risk/var/desk-multi-asset")
                headerResp.status shouldBe HttpStatusCode.OK
                val headerVar = Json.parseToJsonElement(headerResp.bodyAsText())
                    .jsonObject["varValue"]!!.jsonPrimitive.content.toDouble()

                // 2. Cross-book VaR (drives "Sum of books" + Book Contributions table).
                val crossBookResp = client.post("/api/v1/risk/var/cross-book") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"bookIds":["book-a","book-b","book-c"],"portfolioGroupId":"desk-multi-asset"}"""
                    )
                }
                crossBookResp.status shouldBe HttpStatusCode.OK
                val crossBookBody = Json.parseToJsonElement(crossBookResp.bodyAsText()).jsonObject
                val crossBookVar = crossBookBody["varValue"]!!.jsonPrimitive.content.toDouble()
                val totalStandaloneVar = crossBookBody["totalStandaloneVar"]!!.jsonPrimitive.content.toDouble()
                val crossDiversificationBenefit = crossBookBody["diversificationBenefit"]!!
                    .jsonPrimitive.content.toDouble()
                val bookContributionsSum = crossBookBody["bookContributions"]!!.jsonArray.sumOf { row ->
                    row.jsonObject["varContribution"]!!.jsonPrimitive.content.toDouble()
                }

                // 3. Factor Risk Decomposition (Position & Factor Risk section).
                val factorResp = client.get("/api/v1/books/desk-multi-asset/factor-risk/latest")
                factorResp.status shouldBe HttpStatusCode.OK
                val factorBody = Json.parseToJsonElement(factorResp.bodyAsText()).jsonObject
                val factorTotalVar = factorBody["totalVar"]!!.jsonPrimitive.content.toDouble()

                // ---- Reconciliation invariants ----

                // (a) Header VaR == cross-book aggregate VaR (same scope, same run).
                abs(headerVar - crossBookVar) shouldBeLessThanOrEqual tolerance

                // (b) sum(book varContributions) == aggregate VaR.
                //     This is the spec-level decomposition rule — `varContribution`
                //     is a decomposition of the DIVERSIFIED total, not of the
                //     undiversified sum-of-standalones. Today the UI is summing
                //     these to ~$182M while the header is $190K — that's the bug.
                abs(bookContributionsSum - crossBookVar) shouldBeLessThanOrEqual tolerance

                // (c) totalStandaloneVar - diversificationBenefit == aggregate VaR
                //     (specs/risk.allium L627: diversification_benefit = sum(standalone) - aggregate_var).
                abs((totalStandaloneVar - crossDiversificationBenefit) - crossBookVar) shouldBeLessThanOrEqual tolerance

                // (d) Factor decomposition `totalVar` agrees with the same run's aggregate VaR.
                abs(factorTotalVar - crossBookVar) shouldBeLessThanOrEqual tolerance
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("Book Contributions reconcile to aggregate VaR — sum(varContribution) == varValue within tolerance") {
        // Isolates the most-visible piece of the bug: the Book Contributions
        // column in the cross-book response. A real trader reads down the
        // column, sums it in their head, and expects the total to equal the
        // displayed aggregate VaR. The pre-fix demo had this column summing
        // to roughly 18,000× the header.
        val backend = BackendStubServer {
            post("/api/v1/risk/var/cross-book") {
                call.respond(Json.parseToJsonElement(crossBookConsistentJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }

                val resp = client.post("/api/v1/risk/var/cross-book") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookIds":["book-a","book-b","book-c"],"portfolioGroupId":"desk-multi-asset"}""")
                }
                resp.status shouldBe HttpStatusCode.OK

                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val aggregateVar = body["varValue"]!!.jsonPrimitive.content.toDouble()
                val contributionsSum = body["bookContributions"]!!.jsonArray.sumOf { row ->
                    row.jsonObject["varContribution"]!!.jsonPrimitive.content.toDouble()
                }
                abs(contributionsSum - aggregateVar) shouldBeLessThanOrEqual tolerance
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("Gateway rejects an inconsistent cross-book payload where bookContributions do not sum to varValue") {
        // The trader-review caught an orchestrator response that was
        // internally inconsistent — header $190K, bookContributions summing
        // to $182M. The gateway must NOT silently pass a payload like that
        // through to the UI, because there is no client-side recourse: the
        // UI can only render whatever the gateway returns. Either the
        // gateway corrects the inconsistency or it rejects it.
        //
        // We assert the stronger of the two: contracting on the response
        // means the gateway response must satisfy the invariant
        // sum(bookContributions[].varContribution) ≈ varValue. A response
        // that violates that invariant by orders of magnitude must result
        // in an HTTP 502 Bad Gateway (the orchestrator returned garbage)
        // rather than a 200 with garbage in the body.
        val inconsistentJson = """
            {
              "portfolioGroupId":"desk-multi-asset",
              "bookIds":["book-a","book-b","book-c"],
              "calculationType":"PARAMETRIC",
              "confidenceLevel":"CL_95",
              "varValue":1000000.00,
              "expectedShortfall":1250000.00,
              "componentBreakdown":[],
              "bookContributions":[
                {"bookId":"book-a","varContribution":90000000.00,"percentageOfTotal":49.5,"standaloneVar":92000000.00,"diversificationBenefit":2000000.00,"marginalVar":0.0,"incrementalVar":0.0},
                {"bookId":"book-b","varContribution":88000000.00,"percentageOfTotal":48.4,"standaloneVar":90000000.00,"diversificationBenefit":2000000.00,"marginalVar":0.0,"incrementalVar":0.0},
                {"bookId":"book-c","varContribution":4000000.00,"percentageOfTotal":2.1,"standaloneVar":4500000.00,"diversificationBenefit":500000.00,"marginalVar":0.0,"incrementalVar":0.0}
              ],
              "totalStandaloneVar":186500000.00,
              "diversificationBenefit":185500000.00,
              "calculatedAt":"2026-05-28T10:25:00Z"
            }
        """.trimIndent()

        val backend = BackendStubServer {
            post("/api/v1/risk/var/cross-book") {
                call.respond(Json.parseToJsonElement(inconsistentJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }

                val resp = client.post("/api/v1/risk/var/cross-book") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookIds":["book-a","book-b","book-c"],"portfolioGroupId":"desk-multi-asset"}""")
                }

                // Either the gateway rejects the inconsistent payload outright (BadGateway),
                // or it corrects it before responding. What it MUST NOT do is return a 200
                // body whose bookContributions sum is two orders of magnitude away from
                // the headline varValue — that is the production bug.
                if (resp.status == HttpStatusCode.OK) {
                    val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    val aggregateVar = body["varValue"]!!.jsonPrimitive.content.toDouble()
                    val contributionsSum = body["bookContributions"]!!.jsonArray.sumOf { row ->
                        row.jsonObject["varContribution"]!!.jsonPrimitive.content.toDouble()
                    }
                    abs(contributionsSum - aggregateVar) shouldBeLessThanOrEqual tolerance
                } else {
                    resp.status shouldBe HttpStatusCode.BadGateway
                }
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
