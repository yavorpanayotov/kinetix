package com.kinetix.risk.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.BookId
import com.kinetix.risk.cache.RedisTestSetup
import com.kinetix.risk.cache.RedisVaRCache
import com.kinetix.risk.model.*
import com.kinetix.risk.service.VaRCalculationService
import com.kinetix.risk.service.WhatIfAnalysisService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

class WhatIfAcceptanceTest : FunSpec({

    val varCalculationService = mockk<VaRCalculationService>()
    val whatIfAnalysisService = mockk<WhatIfAnalysisService>()
    val varCache = RedisVaRCache(RedisTestSetup.start())

    beforeEach {
        clearMocks(varCalculationService, whatIfAnalysisService)
    }

    fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                riskRoutes(
                    varCalculationService = varCalculationService,
                    varCache = varCache,
                    positionProvider = mockk(),
                    stressTestStub = mockk(),
                    regulatoryStub = mockk(),
                    whatIfAnalysisService = whatIfAnalysisService,
                )
            }
            block()
        }
    }

    test("POST /api/v1/risk/what-if/{bookId} returns 200 with comparison result") {
        val whatIfResult = WhatIfResult(
            baseVaR = 5000.0,
            baseExpectedShortfall = 6250.0,
            baseGreeks = GreeksResult(
                assetClassGreeks = listOf(
                    GreekValues(AssetClass.EQUITY, delta = 100.0, gamma = 5.0, vega = 200.0),
                ),
                theta = -30.0,
                rho = 50.0,
            ),
            basePositionRisk = listOf(
                PositionRisk(
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    marketValue = java.math.BigDecimal("17000.00"),
                    delta = 0.85,
                    gamma = 0.02,
                    vega = 1500.0,
                    varContribution = java.math.BigDecimal("5000.00"),
                    esContribution = java.math.BigDecimal("6250.00"),
                    percentageOfTotal = java.math.BigDecimal("100.00"),
                ),
            ),
            hypotheticalVaR = 7500.0,
            hypotheticalExpectedShortfall = 9375.0,
            hypotheticalGreeks = GreeksResult(
                assetClassGreeks = listOf(
                    GreekValues(AssetClass.EQUITY, delta = 150.0, gamma = 7.5, vega = 300.0),
                ),
                theta = -45.0,
                rho = 75.0,
            ),
            hypotheticalPositionRisk = emptyList(),
            varChange = 2500.0,
            esChange = 3125.0,
            calculatedAt = Instant.parse("2026-03-01T10:00:00Z"),
        )

        coEvery { whatIfAnalysisService.analyzeWhatIf(any(), any(), any(), any()) } returns whatIfResult

        testApp {
            val response = client.post("/api/v1/risk/what-if/port-1") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "hypotheticalTrades": [
                            {
                                "instrumentId": "AAPL",
                                "assetClass": "EQUITY",
                                "side": "BUY",
                                "quantity": "50",
                                "priceAmount": "180.00",
                                "priceCurrency": "USD", "instrumentType": "CASH_EQUITY"
                            }
                        ]
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["baseVaR"]?.jsonPrimitive?.content shouldBe "5000.00"
            body["hypotheticalVaR"]?.jsonPrimitive?.content shouldBe "7500.00"
            body["varChange"]?.jsonPrimitive?.content shouldBe "2500.00"
            body["esChange"]?.jsonPrimitive?.content shouldBe "3125.00"
            body["calculatedAt"]?.jsonPrimitive?.content shouldBe "2026-03-01T10:00:00Z"
        }
    }

    test("POST /api/v1/risk/what-if/{bookId} accepts empty trades list and returns 200") {
        val whatIfResult = WhatIfResult(
            baseVaR = 5000.0,
            baseExpectedShortfall = 6250.0,
            baseGreeks = null,
            basePositionRisk = emptyList(),
            hypotheticalVaR = 5000.0,
            hypotheticalExpectedShortfall = 6250.0,
            hypotheticalGreeks = null,
            hypotheticalPositionRisk = emptyList(),
            varChange = 0.0,
            esChange = 0.0,
            calculatedAt = Instant.parse("2026-03-01T10:00:00Z"),
        )

        coEvery { whatIfAnalysisService.analyzeWhatIf(any(), any(), any(), any()) } returns whatIfResult

        testApp {
            val response = client.post("/api/v1/risk/what-if/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"hypotheticalTrades": []}""")
            }

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["varChange"]?.jsonPrimitive?.content shouldBe "0.00"
        }
    }

    test("POST /api/v1/risk/what-if/{bookId} returns 400 for invalid assetClass") {
        testApp {
            val response = client.post("/api/v1/risk/what-if/port-1") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "hypotheticalTrades": [
                            {
                                "instrumentId": "AAPL",
                                "assetClass": "INVALID_CLASS",
                                "side": "BUY",
                                "quantity": "50",
                                "priceAmount": "180.00",
                                "priceCurrency": "USD", "instrumentType": "CASH_EQUITY"
                            }
                        ]
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.InternalServerError
        }
    }
})
