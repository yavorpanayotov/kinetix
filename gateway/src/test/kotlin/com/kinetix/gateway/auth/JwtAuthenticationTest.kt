package com.kinetix.gateway.auth

import com.kinetix.common.model.*
import com.kinetix.common.security.Role
import com.kinetix.gateway.client.*
import com.kinetix.gateway.module
import com.kinetix.gateway.auth.InMemoryBookAccessService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private fun usd(amount: String) = Money(BigDecimal(amount), USD)

class JwtAuthenticationTest : FunSpec({

    val positionClient = mockk<PositionServiceClient>()
    val riskClient = mockk<RiskServiceClient>()
    val notificationClient = mockk<NotificationServiceClient>()
    val jwtConfig = TestJwtHelper.testJwtConfig()
    val jwkProvider = TestJwtHelper.testJwkProvider()

    beforeEach { clearMocks(positionClient, riskClient, notificationClient) }

    test("unauthenticated request to protected route returns 401") {
        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            val response = client.get("/api/v1/books")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("request with valid JWT token returns 200") {
        coEvery { positionClient.listPortfolios() } returns listOf(
            PortfolioSummary(BookId("port-1")),
        )
        val token = TestJwtHelper.generateToken(roles = listOf(Role.ADMIN))

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            val response = client.get("/api/v1/books") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("request with expired JWT token returns 401") {
        val token = TestJwtHelper.generateExpiredToken()

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            val response = client.get("/api/v1/books") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("request with invalid JWT signature returns 401") {
        val token = TestJwtHelper.generateTokenWithWrongSignature()

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            val response = client.get("/api/v1/books") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("token with unknown kid returns 401") {
        val token = TestJwtHelper.generateTokenWithUnknownKid()

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            val response = client.get("/api/v1/books") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("token with wrong issuer returns 401") {
        val token = TestJwtHelper.generateTokenWithWrongIssuer()

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            val response = client.get("/api/v1/books") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("token with wrong audience returns 401") {
        val token = TestJwtHelper.generateTokenWithWrongAudience()

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            val response = client.get("/api/v1/books") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("token without roles claim authenticates but gets 403 on permission-gated routes") {
        val token = TestJwtHelper.generateTokenWithoutRolesClaim()

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            val response = client.get("/api/v1/books") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("token with TRADER role can access trade endpoints") {
        val trade = Trade(
            tradeId = TradeId("t-1"),
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("100"),
            price = usd("150.00"),
            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = usd("150.00"),
            marketPrice = usd("155.00"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        coEvery { positionClient.bookTrade(any()) } returns BookTradeResult(trade, position)
        val token = TestJwtHelper.generateToken(roles = listOf(Role.TRADER))

        testApplication {
            application {
                module(
                    jwtConfig,
                    positionClient = positionClient,
                    bookAccessService = InMemoryBookAccessService(traderBooks = mapOf("user-1" to setOf("port-1"))),
                    jwkProvider = jwkProvider,
                )
            }
            val response = client.post("/api/v1/books/port-1/trades") {
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
                        "tradedAt": "2025-01-15T10:00:00Z"
                    }
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.Created
        }
    }

    test("token with VIEWER role cannot POST trades (403)") {
        val token = TestJwtHelper.generateToken(roles = listOf(Role.VIEWER))

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            val response = client.post("/api/v1/books/port-1/trades") {
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
                        "tradedAt": "2025-01-15T10:00:00Z"
                    }
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("token with RISK_MANAGER role can calculate VaR") {
        coEvery { riskClient.calculateVaR(any()) } returns ValuationResultSummary(
            bookId = "port-1",
            calculationType = "PARAMETRIC",
            confidenceLevel = "CL_95",
            varValue = 50000.0,
            expectedShortfall = 65000.0,
            componentBreakdown = emptyList(),
            calculatedAt = Instant.parse("2025-01-15T10:00:00Z"),
        )
        val token = TestJwtHelper.generateToken(roles = listOf(Role.RISK_MANAGER))

        testApplication {
            application {
                module(jwtConfig, riskClient = riskClient, jwkProvider = jwkProvider)
            }
            val response = client.post("/api/v1/risk/var/port-1") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("token with COMPLIANCE role can access regulatory endpoints") {
        coEvery { riskClient.calculateFrtb(any<String>()) } returns FrtbResultSummary(
            bookId = "port-1",
            sbmCharges = emptyList(),
            totalSbmCharge = 100000.0,
            grossJtd = 50000.0,
            hedgeBenefit = 10000.0,
            netDrc = 40000.0,
            exoticNotional = 5000.0,
            otherNotional = 3000.0,
            totalRrao = 8000.0,
            totalCapitalCharge = 148000.0,
            calculatedAt = Instant.parse("2025-01-15T10:00:00Z"),
        )
        val token = TestJwtHelper.generateToken(roles = listOf(Role.COMPLIANCE))

        testApplication {
            application {
                module(jwtConfig, riskClient = riskClient, jwkProvider = jwkProvider)
            }
            val response = client.post("/api/v1/regulatory/frtb/port-1") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("health and metrics endpoints do not require authentication") {
        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            client.get("/health").status shouldBe HttpStatusCode.OK
            client.get("/metrics").status shouldBe HttpStatusCode.OK
        }
    }

    test("JWT claims are parsed into UserPrincipal with correct roles") {
        coEvery { positionClient.listPortfolios() } returns emptyList()
        val token = TestJwtHelper.generateToken(
            userId = "user-42",
            username = "trader_joe",
            roles = listOf(Role.TRADER, Role.VIEWER),
        )

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, jwkProvider = jwkProvider)
            }
            val response = client.get("/api/v1/books") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }
})
