package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpPositionServiceClient
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
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

class GatewayPositionContractAcceptanceTest : FunSpec({

    val tradeJson = """
        {
          "tradeId":"t-1",
          "bookId":"port-1",
          "instrumentId":"AAPL",
          "assetClass":"EQUITY",
          "side":"BUY",
          "quantity":"100",
          "price":{"amount":"150.00","currency":"USD"},
          "tradedAt":"2025-01-15T10:00:00Z",
          "instrumentType":"CASH_EQUITY"
        }
    """.trimIndent()

    val positionJson = """
        {
          "bookId":"port-1",
          "instrumentId":"AAPL",
          "assetClass":"EQUITY",
          "quantity":"100",
          "averageCost":{"amount":"150.00","currency":"USD"},
          "marketPrice":{"amount":"155.00","currency":"USD"},
          "marketValue":{"amount":"15500.00","currency":"USD"},
          "unrealizedPnl":{"amount":"500.00","currency":"USD"},
          "instrumentType":"CASH_EQUITY"
        }
    """.trimIndent()

    test("POST /api/v1/books/{bookId}/trades — valid body — returns 201 with trade and position shape") {
        val backend = BackendStubServer {
            post("/api/v1/books/port-1/trades") {
                val responseBody = """{"trade":$tradeJson,"position":$positionJson}"""
                call.respond(HttpStatusCode.Created, Json.parseToJsonElement(responseBody).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.post("/api/v1/books/port-1/trades") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"tradeId":"t-1","instrumentId":"AAPL","assetClass":"EQUITY","side":"BUY","quantity":"100","priceAmount":"150.00","priceCurrency":"USD","tradedAt":"2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}""")
                }
                response.status shouldBe HttpStatusCode.Created
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.containsKey("trade") shouldBe true
                body.containsKey("position") shouldBe true
                val tradeObj = body["trade"]?.jsonObject
                tradeObj?.containsKey("tradeId") shouldBe true
                tradeObj?.containsKey("bookId") shouldBe true
                tradeObj?.containsKey("instrumentId") shouldBe true
                tradeObj?.containsKey("assetClass") shouldBe true

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/books/port-1/trades" }
                recorded.method shouldBe "POST"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/books/{bookId}/positions — returns 200 with position array shape") {
        val backend = BackendStubServer {
            get("/api/v1/books/port-1/positions") {
                call.respond(Json.parseToJsonElement("[$positionJson]").jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.get("/api/v1/books/port-1/positions")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                body.size shouldBe 1
                val pos = body[0].jsonObject
                pos.containsKey("bookId") shouldBe true
                pos.containsKey("instrumentId") shouldBe true
                pos.containsKey("quantity") shouldBe true
                pos.containsKey("marketValue") shouldBe true

                val recorded = backend.recordedRequests.single { it.path == "/api/v1/books/port-1/positions" }
                recorded.method shouldBe "GET"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/books/{bookId}/positions — propagates strategyId, strategyType, and strategyName in the response") {
        val strategyPositionJson = """
            {
              "bookId":"port-1",
              "instrumentId":"AAPL-CALL",
              "assetClass":"EQUITY",
              "quantity":"10",
              "averageCost":{"amount":"5.00","currency":"USD"},
              "marketPrice":{"amount":"8.00","currency":"USD"},
              "marketValue":{"amount":"80.00","currency":"USD"},
              "unrealizedPnl":{"amount":"30.00","currency":"USD"},
              "instrumentType":"EQUITY_OPTION",
              "strategyId":"strat-1",
              "strategyType":"STRADDLE",
              "strategyName":"Sep Straddle"
            }
        """.trimIndent()
        val backend = BackendStubServer {
            get("/api/v1/books/port-1/positions") {
                call.respond(Json.parseToJsonElement("[$strategyPositionJson]").jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.get("/api/v1/books/port-1/positions")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                val pos = body[0].jsonObject
                pos["strategyId"]?.jsonPrimitive?.content shouldBe "strat-1"
                pos["strategyType"]?.jsonPrimitive?.content shouldBe "STRADDLE"
                pos["strategyName"]?.jsonPrimitive?.content shouldBe "Sep Straddle"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("POST /api/v1/books/{bookId}/trades — invalid body — returns 400 with error shape") {
        // Gateway-side validation rejects negative quantity before reaching the upstream; the
        // backend stub need not expose any handlers for this case.
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.post("/api/v1/books/port-1/trades") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"tradeId":"t-1","instrumentId":"AAPL","assetClass":"EQUITY","side":"BUY","quantity":"-100","priceAmount":"150.00","priceCurrency":"USD","tradedAt":"2025-01-15T10:00:00Z","instrumentType":"CASH_EQUITY"}""")
                }
                response.status shouldBe HttpStatusCode.BadRequest
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.containsKey("error") shouldBe true
                body.containsKey("message") shouldBe true

                // Validation happens in the gateway; the backend should not have been called.
                backend.recordedRequests shouldBe emptyList()
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
