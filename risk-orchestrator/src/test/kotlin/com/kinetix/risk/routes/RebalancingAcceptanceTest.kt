package com.kinetix.risk.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.cache.RedisTestSetup
import com.kinetix.risk.cache.RedisVaRCache
import com.kinetix.risk.model.*
import com.kinetix.risk.service.RebalancingWhatIfService
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

class RebalancingAcceptanceTest : FunSpec({

    val varCalculationService = mockk<VaRCalculationService>()
    val whatIfAnalysisService = mockk<WhatIfAnalysisService>()
    val rebalancingService = mockk<RebalancingWhatIfService>()
    val varCache = RedisVaRCache(RedisTestSetup.start())

    beforeEach {
        clearMocks(varCalculationService, whatIfAnalysisService, rebalancingService)
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
                    rebalancingWhatIfService = rebalancingService,
                )
            }
            block()
        }
    }

    val sampleResult = RebalancingWhatIfResult(
        baseVar = 5000.0,
        rebalancedVar = 4000.0,
        varChange = -1000.0,
        varChangePct = -20.0,
        baseExpectedShortfall = 6250.0,
        rebalancedExpectedShortfall = 5000.0,
        esChange = -1250.0,
        baseGreeks = null,
        rebalancedGreeks = null,
        greeksChange = GreeksChange(
            deltaChange = -50.0,
            gammaChange = -2.5,
            vegaChange = -100.0,
            thetaChange = 15.0,
            rhoChange = -25.0,
        ),
        tradeContributions = listOf(
            TradeVarContribution(
                instrumentId = "AAPL",
                side = "SELL",
                quantity = "50",
                marginalVarImpact = -1000.0,
                executionCost = 4.25,
            )
        ),
        estimatedExecutionCost = 4.25,
        calculatedAt = Instant.parse("2026-03-01T10:00:00Z"),
    )

    test("POST /api/v1/risk/what-if/{bookId}/rebalance returns 200 with rebalancing result") {
        coEvery { rebalancingService.analyzeRebalancing(any(), any(), any(), any()) } returns sampleResult

        testApp {
            val response = client.post("/api/v1/risk/what-if/port-1/rebalance") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "trades": [
                            {
                                "instrumentId": "AAPL",
                                "assetClass": "EQUITY",
                                "side": "SELL",
                                "quantity": "50",
                                "priceAmount": "170.00",
                                "priceCurrency": "USD", "instrumentType": "CASH_EQUITY",
                                "bidAskSpreadBps": 5.0
                            }
                        ]
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["baseVar"]?.jsonPrimitive?.content shouldBe "5000.00"
            body["rebalancedVar"]?.jsonPrimitive?.content shouldBe "4000.00"
            body["varChange"]?.jsonPrimitive?.content shouldBe "-1000.00"
            body["varChangePct"]?.jsonPrimitive?.content shouldBe "-20.00"
            body["estimatedExecutionCost"]?.jsonPrimitive?.content shouldBe "4.25"

            val contributions = body["tradeContributions"]?.jsonArray
            contributions?.size shouldBe 1
            contributions?.get(0)?.jsonObject?.get("instrumentId")?.jsonPrimitive?.content shouldBe "AAPL"
            contributions?.get(0)?.jsonObject?.get("marginalVarImpact")?.jsonPrimitive?.content shouldBe "-1000.00"

            val greeksChange = body["greeksChange"]?.jsonObject
            greeksChange?.get("deltaChange")?.jsonPrimitive?.content shouldBe "-50.000000"
        }
    }

    test("POST /api/v1/risk/what-if/{bookId}/rebalance returns 200 with empty trade list") {
        val emptyResult = sampleResult.copy(
            rebalancedVar = 5000.0,
            varChange = 0.0,
            varChangePct = 0.0,
            tradeContributions = emptyList(),
            estimatedExecutionCost = 0.0,
        )
        coEvery { rebalancingService.analyzeRebalancing(any(), any(), any(), any()) } returns emptyResult

        testApp {
            val response = client.post("/api/v1/risk/what-if/port-1/rebalance") {
                contentType(ContentType.Application.Json)
                setBody("""{"trades": []}""")
            }

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["varChange"]?.jsonPrimitive?.content shouldBe "0.00"
            body["tradeContributions"]?.jsonArray?.size shouldBe 0
        }
    }

    test("POST /api/v1/risk/what-if/{bookId}/rebalance returns 400 for invalid assetClass") {
        testApp {
            val response = client.post("/api/v1/risk/what-if/port-1/rebalance") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "trades": [
                            {
                                "instrumentId": "AAPL",
                                "assetClass": "INVALID",
                                "side": "SELL",
                                "quantity": "50",
                                "priceAmount": "170.00",
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
