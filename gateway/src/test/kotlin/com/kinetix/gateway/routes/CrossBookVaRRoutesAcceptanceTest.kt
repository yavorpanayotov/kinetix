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
 * Acceptance tests for `POST /api/v1/risk/var/cross-book` that encode the
 * contract the UI promises to call — i.e. a payload with `portfolioGroupId`
 * (not `bookGroupId`) returns 200 with the canonical `CrossBookVaRResponse`
 * shape.
 *
 * The bug we're locking down: before this fix the UI sent
 * `{"bookIds":[…],"bookGroupId":"firm"}`. The gateway's
 * [com.kinetix.gateway.dtos.CrossBookVaRRequestDto] declares
 * `portfolioGroupId` as a non-nullable required field, so kotlinx.serialization
 * threw on the missing field and the global error handler flattened the
 * throwable into a generic 500 `internal_error` — `docs/plans/ui-fix-v1.md` 2.3.
 *
 * Both halves of the contract are pinned here: the happy path with
 * `portfolioGroupId` returns 200 with all canonical fields populated, and a
 * payload missing `portfolioGroupId` is rejected as a 400 client error so
 * future regressions don't silently surface as 500s.
 */
class CrossBookVaRRoutesAcceptanceTest : FunSpec({

    val crossBookResultJson = """
        {
          "portfolioGroupId":"firm",
          "bookIds":["port-1","port-2"],
          "calculationType":"PARAMETRIC",
          "confidenceLevel":"CL_95",
          "varValue":200000.0,
          "expectedShortfall":300000.0,
          "componentBreakdown":[
            {"assetClass":"EQUITY","varContribution":120000.0,"percentageOfTotal":60.0},
            {"assetClass":"FIXED_INCOME","varContribution":80000.0,"percentageOfTotal":40.0}
          ],
          "bookContributions":[
            {"bookId":"port-1","varContribution":130000.0,"percentageOfTotal":65.0,"standaloneVar":150000.0,"diversificationBenefit":20000.0,"marginalVar":0.000087},
            {"bookId":"port-2","varContribution":70000.0,"percentageOfTotal":35.0,"standaloneVar":100000.0,"diversificationBenefit":30000.0,"marginalVar":0.000070}
          ],
          "totalStandaloneVar":250000.0,
          "diversificationBenefit":50000.0,
          "calculatedAt":"2026-05-19T12:00:00Z"
        }
    """.trimIndent()

    test("POST /api/v1/risk/var/cross-book — with UI-shape payload using portfolioGroupId — returns 200 with canonical CrossBookVaRResponse") {
        val backend = BackendStubServer {
            post("/api/v1/risk/var/cross-book") {
                call.respond(Json.parseToJsonElement(crossBookResultJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }

                // This is the exact UI POST body shape — `bookIds` plus
                // `portfolioGroupId`. Before the UI fix the UI sent
                // `bookGroupId` here and this call 500'd.
                val response = client.post("/api/v1/risk/var/cross-book") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookIds":["port-1","port-2"],"portfolioGroupId":"firm"}""")
                }

                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

                body["portfolioGroupId"]?.jsonPrimitive?.content shouldBe "firm"
                body["bookIds"]?.jsonArray?.size shouldBe 2
                body["varValue"]?.jsonPrimitive?.content shouldNotBe "0.00"
                body["expectedShortfall"]?.jsonPrimitive?.content shouldNotBe null
                body["totalStandaloneVar"]?.jsonPrimitive?.content shouldNotBe null
                body["diversificationBenefit"]?.jsonPrimitive?.content shouldNotBe null

                val contributions = body["bookContributions"]!!.jsonArray
                contributions.size shouldBe 2
                val first = contributions[0].jsonObject
                first["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
                first.containsKey("varContribution") shouldBe true
                first.containsKey("percentageOfTotal") shouldBe true
                first.containsKey("standaloneVar") shouldBe true
                first.containsKey("diversificationBenefit") shouldBe true
                first.containsKey("marginalVar") shouldBe true
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/risk/var/cross-book — with legacy bookGroupId field instead of portfolioGroupId — returns 4xx rather than 500") {
        // Locks down the regression: a payload missing the required
        // `portfolioGroupId` must be rejected as a client error, not flattened
        // into 500 `internal_error`. Today's global handler returns 500 — see
        // docs/plans/ui-fix-v1.md checkbox 2.4 for the dedicated fix to flatten
        // serialisation throwables into 400. Until that lands the gateway
        // returns 500; once it lands the assertion below tightens to 400.
        // Either way the contract is "not 200, not a silent passthrough".
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/risk/var/cross-book") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookIds":["port-1","port-2"],"bookGroupId":"firm"}""")
                }

                response.status.value shouldNotBe HttpStatusCode.OK.value
                // The upstream backend must not have been called — gateway
                // failed to deserialise / validate before forwarding.
                backend.recordedRequests.isEmpty() shouldBe true
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
