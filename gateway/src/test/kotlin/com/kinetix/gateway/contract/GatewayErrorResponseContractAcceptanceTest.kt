package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpPositionServiceClient
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
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

class GatewayErrorResponseContractAcceptanceTest : FunSpec({

    test("invalid trade body sent to gateway returns 400 with { error, message } shape") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(positionClient) }
                val response = client.post("/api/v1/books/port-1/trades") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"tradeId":"t-1","instrumentId":"AAPL","assetClass":"EQUITY","side":"BUY","quantity":"-1","priceAmount":"150","priceCurrency":"USD","tradedAt":"2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}""")
                }
                response.status shouldBe HttpStatusCode.BadRequest
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.containsKey("error") shouldBe true
                body.containsKey("message") shouldBe true

                // Validation should happen at the gateway, not the upstream.
                backend.recordedRequests shouldBe emptyList()
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("price service missing query params returns 400 with { error, message } shape") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val priceClient = HttpPriceServiceClient(httpClient, backend.baseUrl)
            testApplication {
                application { module(priceClient) }
                val response = client.get("/api/v1/prices/AAPL/history")
                response.status shouldBe HttpStatusCode.BadRequest
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.containsKey("error") shouldBe true
                body.containsKey("message") shouldBe true

                backend.recordedRequests shouldBe emptyList()
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
