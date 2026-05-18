package com.kinetix.gateway.auth

import com.kinetix.common.security.Role
import com.kinetix.gateway.client.HttpPositionServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.*

class BookAccessAcceptanceTest : FunSpec({

    val jwtConfig = TestJwtHelper.testJwtConfig()
    val jwkProvider = TestJwtHelper.testJwkProvider()

    val bookAccessService = InMemoryBookAccessService(
        traderBooks = mapOf("trader-1" to setOf("book-A")),
    )

    // Minimal valid position JSON that satisfies the PositionDto deserialization
    val positionJson = """
        {
          "bookId":"book-A",
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

    // Minimal valid book-trade response JSON
    val bookTradeResponseJson = """
        {
          "trade":{
            "tradeId":"t-1","bookId":"book-A","instrumentId":"AAPL","assetClass":"EQUITY",
            "side":"BUY","quantity":"100","price":{"amount":"150.00","currency":"USD"},
            "tradedAt":"2026-01-01T10:00:00Z","instrumentType":"CASH_EQUITY"
          },
          "position":{
            "bookId":"book-A","instrumentId":"AAPL","assetClass":"EQUITY","quantity":"100",
            "averageCost":{"amount":"150.00","currency":"USD"},
            "marketPrice":{"amount":"155.00","currency":"USD"},
            "marketValue":{"amount":"15500.00","currency":"USD"},
            "unrealizedPnl":{"amount":"500.00","currency":"USD"},
            "instrumentType":"CASH_EQUITY"
          }
        }
    """.trimIndent()

    test("TRADER can access positions in their own book") {
        val backend = BackendStubServer {
            get("/api/v1/books/book-A/positions") {
                call.respondText("[$positionJson]", ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "trader-1", roles = listOf(Role.TRADER))

            testApplication {
                application { module(jwtConfig, positionClient = positionClient, bookAccessService = bookAccessService, jwkProvider = jwkProvider) }
                val response = client.get("/api/v1/books/book-A/positions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("TRADER cannot access positions in a book not assigned to them (403)") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "trader-1", roles = listOf(Role.TRADER))

            testApplication {
                application { module(jwtConfig, positionClient = positionClient, bookAccessService = bookAccessService, jwkProvider = jwkProvider) }
                val response = client.get("/api/v1/books/book-B/positions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("RISK_MANAGER can access positions in any book") {
        val backend = BackendStubServer {
            get("/api/v1/books/book-B/positions") {
                call.respondText("[]", ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "rm-1", roles = listOf(Role.RISK_MANAGER))

            testApplication {
                application { module(jwtConfig, positionClient = positionClient, bookAccessService = bookAccessService, jwkProvider = jwkProvider) }
                val response = client.get("/api/v1/books/book-B/positions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("TRADER cannot book a trade in a book not assigned to them (403)") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "trader-1", roles = listOf(Role.TRADER))

            testApplication {
                application { module(jwtConfig, positionClient = positionClient, bookAccessService = bookAccessService, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/books/book-B/trades") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""
                        {
                            "tradeId": "t-1",
                            "instrumentId": "AAPL",
                            "assetClass": "EQUITY",
                            "side": "BUY",
                            "quantity": "100",
                            "priceAmount": "150.00",
                            "priceCurrency": "USD",
                            "tradedAt": "2026-01-01T10:00:00Z",
                            "instrumentType": "CASH_EQUITY"
                        }
                    """.trimIndent())
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("TRADER can book a trade in their own book") {
        val backend = BackendStubServer {
            post("/api/v1/books/book-A/trades") {
                call.respondText(bookTradeResponseJson, ContentType.Application.Json, HttpStatusCode.Created)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "trader-1", roles = listOf(Role.TRADER))

            testApplication {
                application { module(jwtConfig, positionClient = positionClient, bookAccessService = bookAccessService, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/books/book-A/trades") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""
                        {
                            "tradeId": "t-1",
                            "instrumentId": "AAPL",
                            "assetClass": "EQUITY",
                            "side": "BUY",
                            "quantity": "100",
                            "priceAmount": "150.00",
                            "priceCurrency": "USD",
                            "tradedAt": "2026-01-01T10:00:00Z",
                            "instrumentType": "CASH_EQUITY"
                        }
                    """.trimIndent())
                }
                response.status shouldBe HttpStatusCode.Created
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
