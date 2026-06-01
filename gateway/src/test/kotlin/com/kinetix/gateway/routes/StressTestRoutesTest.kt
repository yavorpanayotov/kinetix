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

private val sampleStressResult = StressTestResultSummary(
    scenarioName = "GFC_2008",
    baseVar = 100000.00,
    stressedVar = 300000.00,
    pnlImpact = -550000.00,
    assetClassImpacts = listOf(
        AssetClassImpactItem(
            assetClass = "EQUITY",
            baseExposure = 1000000.00,
            stressedExposure = 600000.00,
            pnlImpact = -400000.00,
        ),
    ),
    calculatedAt = Instant.parse("2025-01-15T10:00:00Z"),
)

private val sampleGreeksResult = GreeksResultSummary(
    bookId = "port-1",
    assetClassGreeks = listOf(
        GreekValuesItem(
            assetClass = "EQUITY",
            delta = 1234.56,
            gamma = 78.90,
            vega = 5678.12,
        ),
    ),
    theta = -123.45,
    rho = 456.78,
    calculatedAt = Instant.parse("2025-01-15T10:00:00Z"),
)

class StressTestRoutesTest : FunSpec({

    val riskClient = mockk<RiskServiceClient>()

    beforeEach {
        clearMocks(riskClient)
        // Provide default stubs for VaR interface methods
        coEvery { riskClient.calculateVaR(any()) } returns null
        coEvery { riskClient.getLatestVaR(any()) } returns null
    }

    test("POST /api/v1/risk/stress/{bookId} returns 200 with stress test result") {
        coEvery { riskClient.runStressTest(any()) } returns sampleStressResult

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/stress/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"scenarioName":"GFC_2008"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["scenarioName"]?.jsonPrimitive?.content shouldBe "GFC_2008"
            body["baseVar"]?.jsonPrimitive?.content shouldBe "100000.00"
            body["stressedVar"]?.jsonPrimitive?.content shouldBe "300000.00"
            body["pnlImpact"]?.jsonPrimitive?.content shouldBe "-550000.00"
            val impacts = body["assetClassImpacts"]?.jsonArray
            impacts?.size shouldBe 1
            impacts!![0].jsonObject["assetClass"]?.jsonPrimitive?.content shouldBe "EQUITY"
        }
    }

    test("GET /api/v1/risk/stress/scenarios returns scenario list") {
        coEvery { riskClient.listScenarios() } returns listOf(
            "GFC_2008", "COVID_2020", "TAPER_TANTRUM_2013", "EURO_CRISIS_2011",
        )

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/risk/stress/scenarios")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 4
            body[0].jsonPrimitive.content shouldBe "GFC_2008"
        }
    }

    test("POST /api/v1/risk/greeks/{bookId} returns 200 with Greeks result") {
        coEvery { riskClient.calculateVaR(any()) } returns ValuationResultSummary(
            bookId = "port-1",
            calculationType = "PARAMETRIC",
            confidenceLevel = "CL_95",
            varValue = 50000.0,
            expectedShortfall = 62500.0,
            componentBreakdown = emptyList(),
            calculatedAt = Instant.parse("2025-01-15T10:00:00Z"),
            greeks = sampleGreeksResult,
        )

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/greeks/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
            val greeks = body["assetClassGreeks"]?.jsonArray
            greeks?.size shouldBe 1
            greeks!![0].jsonObject["assetClass"]?.jsonPrimitive?.content shouldBe "EQUITY"
            body["theta"]?.jsonPrimitive?.content shouldBe "-123.450000"
            body["rho"]?.jsonPrimitive?.content shouldBe "456.780000"
        }
    }

    test("POST /api/v1/risk/stress/{bookId} returns 404 when service returns null") {
        coEvery { riskClient.runStressTest(any()) } returns null

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/stress/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"scenarioName":"NONEXISTENT"}""")
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/batch returns ranked results with worstScenarioName") {
        coEvery { riskClient.runBatchStressTest(any()) } returns BatchStressRunSummary(
            results = listOf(
                BatchScenarioResultItem(
                    scenarioName = "GFC_2008",
                    baseVar = "50000.00",
                    stressedVar = "80000.00",
                    pnlImpact = "-400000.00",
                ),
                BatchScenarioResultItem(
                    scenarioName = "COVID_2020",
                    baseVar = "50000.00",
                    stressedVar = "70000.00",
                    pnlImpact = "-150000.00",
                ),
            ),
            failedScenarios = emptyList(),
            worstScenarioName = "GFC_2008",
            worstPnlImpact = "-400000.00",
        )

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/stress/port-1/batch") {
                contentType(ContentType.Application.Json)
                setBody("""{"scenarioNames":["GFC_2008","COVID_2020"]}""")
            }

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val results = body["results"]!!.jsonArray
            results.size shouldBe 2
            results[0].jsonObject["scenarioName"]!!.jsonPrimitive.content shouldBe "GFC_2008"
            body["worstScenarioName"]!!.jsonPrimitive.content shouldBe "GFC_2008"
            body["worstPnlImpact"]!!.jsonPrimitive.content shouldBe "-400000.00"
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/batch returns failedScenarios in response") {
        coEvery { riskClient.runBatchStressTest(any()) } returns BatchStressRunSummary(
            results = listOf(
                BatchScenarioResultItem(
                    scenarioName = "GFC_2008",
                    baseVar = "50000.00",
                    stressedVar = "80000.00",
                    pnlImpact = "-400000.00",
                ),
            ),
            failedScenarios = listOf(
                BatchScenarioFailureItem(
                    scenarioName = "BROKEN",
                    errorMessage = "engine unavailable",
                ),
            ),
            worstScenarioName = "GFC_2008",
            worstPnlImpact = "-400000.00",
        )

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/stress/port-1/batch") {
                contentType(ContentType.Application.Json)
                setBody("""{"scenarioNames":["GFC_2008","BROKEN"]}""")
            }

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val failures = body["failedScenarios"]!!.jsonArray
            failures.size shouldBe 1
            failures[0].jsonObject["scenarioName"]!!.jsonPrimitive.content shouldBe "BROKEN"
        }
    }

    test("GET /api/v1/risk/stress/{bookId}/batch returns the stored batch result") {
        coEvery { riskClient.getLatestStressBatch("port-1") } returns BatchStressRunSummary(
            results = listOf(
                BatchScenarioResultItem(
                    scenarioName = "GFC_2008",
                    baseVar = "50000.00",
                    stressedVar = "80000.00",
                    pnlImpact = "-400000.00",
                ),
                BatchScenarioResultItem(
                    scenarioName = "COVID_2020",
                    baseVar = "50000.00",
                    stressedVar = "70000.00",
                    pnlImpact = "-150000.00",
                ),
            ),
            failedScenarios = emptyList(),
            worstScenarioName = "GFC_2008",
            worstPnlImpact = "-400000.00",
        )

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/risk/stress/port-1/batch")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val results = body["results"]!!.jsonArray
            results.size shouldBe 2
            results[0].jsonObject["scenarioName"]!!.jsonPrimitive.content shouldBe "GFC_2008"
            body["worstScenarioName"]!!.jsonPrimitive.content shouldBe "GFC_2008"
        }
    }

    test("GET /api/v1/risk/stress/{bookId}/batch returns 404 when no batch has been stored") {
        coEvery { riskClient.getLatestStressBatch("empty-book") } returns null

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/risk/stress/empty-book/batch")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST /api/v1/risk/stress/{bookId}/canned/{scenarioName} returns the canned tile payload") {
        coEvery {
            riskClient.runCannedStressScenario("port-rates", "+100BPS_PARALLEL")
        } returns buildJsonObject {
            put("bookId", "port-rates")
            put("scenario", "+100BPS_PARALLEL")
            put("deltaPv", "-8000.00")
            put("asOf", "2026-05-26T09:00:00Z")
        }

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/stress/port-rates/canned/+100BPS_PARALLEL") {
                contentType(ContentType.Application.Json)
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]?.jsonPrimitive?.content shouldBe "port-rates"
            body["scenario"]?.jsonPrimitive?.content shouldBe "+100BPS_PARALLEL"
            body["deltaPv"]?.jsonPrimitive?.content shouldBe "-8000.00"
        }
    }

    test("GET /api/v1/risk/stress/{bookId}/canned returns 404 when no result has been cached") {
        coEvery { riskClient.getCannedStressScenario("empty-book") } returns null

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/risk/stress/empty-book/canned")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})
