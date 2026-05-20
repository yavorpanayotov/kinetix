package com.kinetix.gateway.routes

import com.kinetix.gateway.client.*
import com.kinetix.gateway.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*
import java.time.Instant

private val sampleRebalancingResult = RebalancingWhatIfResultSummary(
    baseVar = "5000.00",
    rebalancedVar = "4000.00",
    varChange = "-1000.00",
    varChangePct = "-20.00",
    baseExpectedShortfall = "6250.00",
    rebalancedExpectedShortfall = "5000.00",
    esChange = "-1250.00",
    baseGreeks = null,
    rebalancedGreeks = null,
    greeksChange = GreeksChangeSummary(
        deltaChange = "-50.000000",
        gammaChange = "-2.500000",
        vegaChange = "-100.000000",
        thetaChange = "15.000000",
        rhoChange = "-25.000000",
    ),
    tradeContributions = listOf(
        TradeVarContributionSummary(
            instrumentId = "AAPL",
            side = "SELL",
            quantity = "50",
            marginalVarImpact = "-1000.00",
            executionCost = "4.25",
        )
    ),
    estimatedExecutionCost = "4.25",
    calculatedAt = "2026-03-01T10:00:00Z",
)

private val sampleWhatIfResult = WhatIfResultSummary(
    baseVaR = "5000.00",
    baseExpectedShortfall = "6250.00",
    baseGreeks = null,
    basePositionRisk = emptyList(),
    hypotheticalVaR = "7500.00",
    hypotheticalExpectedShortfall = "9375.00",
    hypotheticalGreeks = null,
    hypotheticalPositionRisk = emptyList(),
    varChange = "2500.00",
    esChange = "3125.00",
    calculatedAt = "2026-03-01T10:00:00Z",
)

class WhatIfRoutesTest : FunSpec({

    val riskClient = mockk<RiskServiceClient>()

    beforeEach {
        clearMocks(riskClient)
        coEvery { riskClient.calculateVaR(any()) } returns null
        coEvery { riskClient.getLatestVaR(any()) } returns null
    }

    test("POST /api/v1/risk/what-if/{bookId} proxies 200 with result") {
        coEvery { riskClient.runWhatIf(any()) } returns sampleWhatIfResult

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/what-if/port-1") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "hypotheticalTrades": [
                            {
                                "instrumentId": "SPY",
                                "assetClass": "EQUITY",
                                "side": "BUY",
                                "quantity": "100",
                                "priceAmount": "450.00",
                                "priceCurrency": "USD",
                                "instrumentType": "CASH_EQUITY"
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
        }
    }

    test("POST /api/v1/risk/what-if/{bookId} propagates upstream errors") {
        coEvery { riskClient.runWhatIf(any()) } throws
            com.kinetix.gateway.client.UpstreamErrorException(502, "risk-orchestrator unavailable")

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/what-if/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"hypotheticalTrades": []}""")
            }

            response.status shouldBe HttpStatusCode.BadGateway
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["message"]?.jsonPrimitive?.content shouldBe "risk-orchestrator unavailable"
        }
    }

    test("POST /api/v1/risk/what-if/{bookId} passes request params correctly") {
        coEvery { riskClient.runWhatIf(any()) } returns sampleWhatIfResult

        testApplication {
            application { module(riskClient) }
            client.post("/api/v1/risk/what-if/my-portfolio") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "hypotheticalTrades": [
                            {
                                "instrumentId": "AAPL",
                                "assetClass": "DERIVATIVE",
                                "side": "SELL",
                                "quantity": "200",
                                "priceAmount": "175.00",
                                "priceCurrency": "EUR",
                                "instrumentType": "EQUITY_OPTION"
                            }
                        ],
                        "calculationType": "MONTE_CARLO",
                        "confidenceLevel": "CL_99"
                    }
                    """.trimIndent()
                )
            }

            val slot = slot<WhatIfRequestParams>()
            coVerify { riskClient.runWhatIf(capture(slot)) }

            slot.captured.bookId shouldBe "my-portfolio"
            slot.captured.hypotheticalTrades.size shouldBe 1
            slot.captured.hypotheticalTrades[0].instrumentId shouldBe "AAPL"
            slot.captured.hypotheticalTrades[0].assetClass shouldBe "DERIVATIVE"
            slot.captured.hypotheticalTrades[0].side shouldBe "SELL"
            slot.captured.hypotheticalTrades[0].quantity shouldBe "200"
            slot.captured.hypotheticalTrades[0].priceAmount shouldBe "175.00"
            slot.captured.hypotheticalTrades[0].priceCurrency shouldBe "EUR"
            slot.captured.hypotheticalTrades[0].instrumentType shouldBe "EQUITY_OPTION"
            slot.captured.calculationType shouldBe "MONTE_CARLO"
            slot.captured.confidenceLevel shouldBe "CL_99"
        }
    }

    test("POST /api/v1/risk/what-if/{bookId}/rebalance proxies 200 with rebalancing result") {
        coEvery { riskClient.runRebalancing(any()) } returns sampleRebalancingResult

        testApplication {
            application { module(riskClient) }
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
                                "priceCurrency": "USD",
                                "instrumentType": "CASH_EQUITY",
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
        }
    }

    test("POST /api/v1/risk/what-if/{bookId}/rebalance passes request params correctly") {
        coEvery { riskClient.runRebalancing(any()) } returns sampleRebalancingResult

        testApplication {
            application { module(riskClient) }
            client.post("/api/v1/risk/what-if/my-portfolio/rebalance") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "trades": [
                            {
                                "instrumentId": "TSLA",
                                "assetClass": "EQUITY",
                                "side": "BUY",
                                "quantity": "100",
                                "priceAmount": "250.00",
                                "priceCurrency": "USD",
                                "instrumentType": "CASH_EQUITY",
                                "bidAskSpreadBps": 10.0
                            }
                        ],
                        "calculationType": "HISTORICAL",
                        "confidenceLevel": "CL_99"
                    }
                    """.trimIndent()
                )
            }

            val slot = slot<RebalancingRequestParams>()
            coVerify { riskClient.runRebalancing(capture(slot)) }

            slot.captured.bookId shouldBe "my-portfolio"
            slot.captured.trades.size shouldBe 1
            slot.captured.trades[0].instrumentId shouldBe "TSLA"
            slot.captured.trades[0].instrumentType shouldBe "CASH_EQUITY"
            slot.captured.trades[0].bidAskSpreadBps shouldBe 10.0
            slot.captured.calculationType shouldBe "HISTORICAL"
            slot.captured.confidenceLevel shouldBe "CL_99"
        }
    }
})
