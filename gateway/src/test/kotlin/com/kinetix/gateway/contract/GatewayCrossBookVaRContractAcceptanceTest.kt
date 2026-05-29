package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

class GatewayCrossBookVaRContractAcceptanceTest : FunSpec({

    val crossBookResultJson = """
        {
          "portfolioGroupId":"desk-fx",
          "bookIds":["book-1","book-2"],
          "calculationType":"PARAMETRIC",
          "confidenceLevel":"CL_95",
          "varValue":120000.0,
          "expectedShortfall":155000.0,
          "componentBreakdown":[{"assetClass":"EQUITY","varContribution":80000.0,"percentageOfTotal":66.67}],
          "bookContributions":[
            {"bookId":"book-1","varContribution":70000.0,"percentageOfTotal":58.33,"standaloneVar":85000.0,"diversificationBenefit":15000.0,"marginalVar":0.000045},
            {"bookId":"book-2","varContribution":50000.0,"percentageOfTotal":41.67,"standaloneVar":65000.0,"diversificationBenefit":15000.0,"marginalVar":0.000032}
          ],
          "totalStandaloneVar":150000.0,
          "diversificationBenefit":30000.0,
          "calculatedAt":"2025-06-15T10:00:00Z"
        }
    """.trimIndent()

    val singleBookResultJson = """
        {
          "bookId":"port-1",
          "calculationType":"PARAMETRIC",
          "confidenceLevel":"CL_95",
          "varValue":"50000.00",
          "expectedShortfall":"65000.00",
          "componentBreakdown":[{"assetClass":"EQUITY","varContribution":"30000.00","percentageOfTotal":"60.00"}],
          "calculatedAt":"2025-01-15T10:00:00Z",
          "pvValue":"1250000.00"
        }
    """.trimIndent()

    test("gateway routing to cross-book VaR — POST /api/v1/risk/var/cross-book with valid request — returns 200 with cross-book VaR response shape") {
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
                val response = client.post("/api/v1/risk/var/cross-book") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookIds":["book-1","book-2"],"portfolioGroupId":"desk-fx"}""")
                }
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["portfolioGroupId"]?.jsonPrimitive?.content shouldBe "desk-fx"
                body["bookIds"]?.jsonArray?.size shouldBe 2
                body.containsKey("varValue") shouldBe true
                body.containsKey("expectedShortfall") shouldBe true
                body.containsKey("calculatedAt") shouldBe true
                body.containsKey("totalStandaloneVar") shouldBe true
                body.containsKey("diversificationBenefit") shouldBe true

                val contributions = body["bookContributions"]?.jsonArray
                contributions?.size shouldBe 2
                val first = contributions?.get(0)?.jsonObject
                first?.containsKey("bookId") shouldBe true
                first?.containsKey("varContribution") shouldBe true
                first?.containsKey("percentageOfTotal") shouldBe true
                first?.containsKey("standaloneVar") shouldBe true
                first?.containsKey("diversificationBenefit") shouldBe true
                first?.containsKey("marginalVar") shouldBe true

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/risk/var/cross-book" }
                recorded.method shouldBe "POST"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("gateway routing to cross-book VaR — POST /api/v1/risk/var/cross-book with empty bookIds — returns 400") {
        // Gateway-side validation rejects empty bookIds before reaching upstream.
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/risk/var/cross-book") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookIds":[],"portfolioGroupId":"desk"}""")
                }
                response.status shouldBe HttpStatusCode.BadRequest
                val body = response.bodyAsText()
                body shouldContain "BAD_REQUEST"

                backend.recordedRequests shouldBe emptyList()
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("gateway routing to cross-book VaR — POST /api/v1/risk/var/cross-book with invalid confidence level — returns 400") {
        // Gateway-side validation rejects unsupported confidence levels.
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/risk/var/cross-book") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookIds":["book-1"],"portfolioGroupId":"desk","confidenceLevel":"CL_50"}""")
                }
                response.status shouldBe HttpStatusCode.BadRequest
                val body = response.bodyAsText()
                body shouldContain "BAD_REQUEST"

                backend.recordedRequests shouldBe emptyList()
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("gateway routing to cross-book VaR — GET /api/v1/risk/var/cross-book/{groupId} with cached result — returns 200 with correct fields") {
        val cachedJson = """
            {
              "portfolioGroupId":"desk-fx",
              "bookIds":["book-1"],
              "calculationType":"PARAMETRIC",
              "confidenceLevel":"CL_99",
              "varValue":90000.0,
              "expectedShortfall":110000.0,
              "componentBreakdown":[],
              "bookContributions":[
                {"bookId":"book-1","varContribution":90000.0,"percentageOfTotal":100.0,"standaloneVar":90000.0,"diversificationBenefit":0.0,"marginalVar":0.0}
              ],
              "totalStandaloneVar":90000.0,
              "diversificationBenefit":0.0,
              "calculatedAt":"2025-06-15T12:00:00Z"
            }
        """.trimIndent()
        val backend = BackendStubServer {
            get("/api/v1/risk/var/cross-book/desk-fx") {
                call.respond(Json.parseToJsonElement(cachedJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/var/cross-book/desk-fx")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["portfolioGroupId"]?.jsonPrimitive?.content shouldBe "desk-fx"
                body.containsKey("varValue") shouldBe true
                body.containsKey("expectedShortfall") shouldBe true
                body.containsKey("bookContributions") shouldBe true
                body.containsKey("calculatedAt") shouldBe true

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/risk/var/cross-book/desk-fx" }
                recorded.method shouldBe "GET"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("gateway routing to cross-book VaR — GET /api/v1/risk/var/cross-book/{groupId} when not found — returns 404") {
        val backend = BackendStubServer {
            get("/api/v1/risk/var/cross-book/unknown-group") {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.get("/api/v1/risk/var/cross-book/unknown-group")
                response.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("gateway routing to cross-book VaR — POST /api/v1/risk/var/port-1 (single-book VaR endpoint) still works — regression test") {
        val backend = BackendStubServer {
            post("/api/v1/risk/var/port-1") {
                call.respond(Json.parseToJsonElement(singleBookResultJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/risk/var/port-1") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"calculationType":"PARAMETRIC","confidenceLevel":"CL_95"}""")
                }
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
                body["calculationType"]?.jsonPrimitive?.content shouldBe "PARAMETRIC"
                body.containsKey("varValue") shouldBe true
                body.containsKey("expectedShortfall") shouldBe true
                body.containsKey("componentBreakdown") shouldBe true
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
