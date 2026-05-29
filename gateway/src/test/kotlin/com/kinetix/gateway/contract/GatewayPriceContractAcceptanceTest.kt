package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpPriceServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
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
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

class GatewayPriceContractAcceptanceTest : FunSpec({

    val pricePointJson = """
        {
          "instrumentId":"AAPL",
          "price":{"amount":"150.00","currency":"USD"},
          "timestamp":"2025-01-15T10:00:00Z",
          "source":"EXCHANGE"
        }
    """.trimIndent()

    test("GET /api/v1/prices/{instrumentId}/latest — returns 200 with price point shape") {
        val backend = BackendStubServer {
            get("/api/v1/prices/AAPL/latest") {
                call.respond(Json.parseToJsonElement(pricePointJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val priceClient = HttpPriceServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(priceClient) }
                val response = client.get("/api/v1/prices/AAPL/latest")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.containsKey("instrumentId") shouldBe true
                body.containsKey("price") shouldBe true
                body.containsKey("timestamp") shouldBe true
                body.containsKey("source") shouldBe true
                body["instrumentId"]?.jsonPrimitive?.content shouldBe "AAPL"

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/prices/AAPL/latest" }
                recorded.method shouldBe "GET"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/prices/{instrumentId}/latest — upstream 404 — returns 404") {
        val backend = BackendStubServer {
            get("/api/v1/prices/UNKNOWN/latest") {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val priceClient = HttpPriceServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(priceClient) }
                val response = client.get("/api/v1/prices/UNKNOWN/latest")
                response.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/prices/{instrumentId}/history — missing required params — returns 400") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val priceClient = HttpPriceServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(priceClient) }
                val response = client.get("/api/v1/prices/AAPL/history")
                response.status shouldBe HttpStatusCode.BadRequest
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.containsKey("code") shouldBe true
                body.containsKey("message") shouldBe true

                backend.recordedRequests shouldBe emptyList()
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
