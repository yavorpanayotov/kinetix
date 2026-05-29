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
import io.ktor.server.routing.post
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

class GatewayRiskContractAcceptanceTest : FunSpec({

    val varResponseJson = """
        {
          "bookId":"port-1",
          "calculationType":"PARAMETRIC",
          "confidenceLevel":"CL_95",
          "varValue":"50000.00",
          "expectedShortfall":"65000.00",
          "componentBreakdown":[
            {"assetClass":"EQUITY","varContribution":"30000.00","percentageOfTotal":"60.00"}
          ],
          "calculatedAt":"2025-01-15T10:00:00Z",
          "pvValue":"1250000.00"
        }
    """.trimIndent()

    test("gateway routing to risk-orchestrator — POST /api/v1/risk/var/{bookId} with valid request — returns 200 with VaR response shape including componentBreakdown") {
        val backend = BackendStubServer {
            post("/api/v1/risk/var/port-1") {
                call.respond(Json.parseToJsonElement(varResponseJson).jsonObject)
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
                body["confidenceLevel"]?.jsonPrimitive?.content shouldBe "CL_95"
                body.containsKey("varValue") shouldBe true
                body.containsKey("expectedShortfall") shouldBe true
                body.containsKey("componentBreakdown") shouldBe true
                body["componentBreakdown"]?.jsonArray?.size shouldBe 1
                val comp = body["componentBreakdown"]?.jsonArray?.get(0)?.jsonObject
                comp?.containsKey("assetClass") shouldBe true
                comp?.containsKey("varContribution") shouldBe true
                comp?.containsKey("percentageOfTotal") shouldBe true

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/risk/var/port-1" }
                recorded.method shouldBe "POST"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("gateway routing to risk-orchestrator — POST /api/v1/risk/var/{bookId} with invalid calculationType — returns 400 with ApiError shape") {
        // Gateway-side validation rejects unknown calculationType before reaching the upstream.
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(riskClient) }
                val response = client.post("/api/v1/risk/var/port-1") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"calculationType":"INVALID_TYPE"}""")
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
})
