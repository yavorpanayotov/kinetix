package com.kinetix.gateway.routes

import com.kinetix.gateway.client.ComponentBreakdownItem
import com.kinetix.gateway.client.PositionGreekSummary
import com.kinetix.gateway.client.RiskServiceClient
import com.kinetix.gateway.client.VaRCalculationParams
import com.kinetix.gateway.client.ValuationResultSummary
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

private val sampleResult = ValuationResultSummary(
    bookId = "port-1",
    calculationType = "HISTORICAL",
    confidenceLevel = "CL_95",
    varValue = 1234567.89,
    expectedShortfall = 1567890.12,
    componentBreakdown = listOf(
        ComponentBreakdownItem(
            assetClass = "EQUITY",
            varContribution = 800000.00,
            percentageOfTotal = 64.85,
        ),
    ),
    calculatedAt = Instant.parse("2025-01-15T10:00:00Z"),
    pvValue = 9876543.21,
)

class VaRRoutesTest : FunSpec({

    val riskClient = mockk<RiskServiceClient>()

    beforeEach { clearMocks(riskClient) }

    // --- POST /api/v1/risk/var/{bookId} ---

    test("POST /api/v1/risk/var/{bookId} returns 200 with VaR result") {
        coEvery { riskClient.calculateVaR(any()) } returns sampleResult

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/var/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"calculationType":"HISTORICAL","confidenceLevel":"CL_95","timeHorizonDays":"10","numSimulations":"50000"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
            body["calculationType"]?.jsonPrimitive?.content shouldBe "HISTORICAL"
            body["confidenceLevel"]?.jsonPrimitive?.content shouldBe "CL_95"
            body["varValue"]?.jsonPrimitive?.content shouldBe "1234567.89"
            body["expectedShortfall"]?.jsonPrimitive?.content shouldBe "1567890.12"
            val breakdown = body["componentBreakdown"]?.jsonArray
            breakdown?.size shouldBe 1
            breakdown!![0].jsonObject["assetClass"]?.jsonPrimitive?.content shouldBe "EQUITY"
            breakdown[0].jsonObject["varContribution"]?.jsonPrimitive?.content shouldBe "800000.00"
            breakdown[0].jsonObject["percentageOfTotal"]?.jsonPrimitive?.content shouldBe "64.85"
            body["calculatedAt"]?.jsonPrimitive?.content shouldBe "2025-01-15T10:00:00Z"
            body["pvValue"]?.jsonPrimitive?.content shouldBe "9876543.21"
        }
    }

    test("POST returns 404 when no positions (service returns null)") {
        coEvery { riskClient.calculateVaR(any()) } returns null

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/var/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"calculationType":"HISTORICAL"}""")
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST with all optional fields specified") {
        val paramsSlot = slot<VaRCalculationParams>()
        coEvery { riskClient.calculateVaR(capture(paramsSlot)) } returns sampleResult

        testApplication {
            application { module(riskClient) }
            client.post("/api/v1/risk/var/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"calculationType":"MONTE_CARLO","confidenceLevel":"CL_99","timeHorizonDays":"10","numSimulations":"50000"}""")
            }
            paramsSlot.captured.bookId shouldBe "port-1"
            paramsSlot.captured.calculationType shouldBe "MONTE_CARLO"
            paramsSlot.captured.confidenceLevel shouldBe "CL_99"
            paramsSlot.captured.timeHorizonDays shouldBe 10
            paramsSlot.captured.numSimulations shouldBe 50000
        }
    }

    test("POST with default values (empty body)") {
        val paramsSlot = slot<VaRCalculationParams>()
        coEvery { riskClient.calculateVaR(capture(paramsSlot)) } returns sampleResult

        testApplication {
            application { module(riskClient) }
            client.post("/api/v1/risk/var/port-1") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            paramsSlot.captured.bookId shouldBe "port-1"
            paramsSlot.captured.calculationType shouldBe "PARAMETRIC"
            paramsSlot.captured.confidenceLevel shouldBe "CL_95"
            paramsSlot.captured.timeHorizonDays shouldBe 1
            paramsSlot.captured.numSimulations shouldBe 10_000
        }
    }

    test("POST returns 400 for invalid calculationType") {
        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/risk/var/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"calculationType":"INVALID"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body.containsKey("code") shouldBe true
            body.containsKey("message") shouldBe true
        }
    }

    // --- GET /api/v1/risk/var/{bookId} ---

    test("GET /api/v1/risk/var/{bookId} returns 200 with latest VaR result") {
        coEvery { riskClient.getLatestVaR("port-1") } returns sampleResult

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/risk/var/port-1")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
            body["varValue"]?.jsonPrimitive?.content shouldBe "1234567.89"
        }
    }

    test("GET returns 404 when no VaR result exists") {
        coEvery { riskClient.getLatestVaR("port-1") } returns null

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/risk/var/port-1")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    // --- Valuation Date ---

    test("GET forwards valuationDate query parameter to the client") {
        val historicalResult = sampleResult.copy(valuationDate = "2025-01-14")
        coEvery { riskClient.getLatestVaR("port-1", "2025-01-14") } returns historicalResult

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/risk/var/port-1?valuationDate=2025-01-14")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["valuationDate"]?.jsonPrimitive?.content shouldBe "2025-01-14"
            coVerify { riskClient.getLatestVaR("port-1", "2025-01-14") }
        }
    }

    test("GET without valuationDate passes null to the client") {
        coEvery { riskClient.getLatestVaR("port-1", null) } returns sampleResult

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/risk/var/port-1")
            response.status shouldBe HttpStatusCode.OK
            coVerify { riskClient.getLatestVaR("port-1", null) }
        }
    }

    // --- Position Greeks ---

    test("GET returns positionGreeks in response when result has option Greeks") {
        val resultWithGreeks = sampleResult.copy(
            positionGreeks = listOf(
                PositionGreekSummary(
                    instrumentId = "AAPL-OPT-JAN25-150C",
                    delta = 0.65,
                    gamma = 0.03,
                    vega = 125.4,
                    theta = -8.2,
                    rho = 12.1,
                ),
            ),
        )
        coEvery { riskClient.getLatestVaR("port-1", null) } returns resultWithGreeks

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/risk/var/port-1")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val positionGreeks = body["positionGreeks"]?.jsonArray
            positionGreeks?.size shouldBe 1
            val greek = positionGreeks!![0].jsonObject
            greek["instrumentId"]?.jsonPrimitive?.content shouldBe "AAPL-OPT-JAN25-150C"
            greek["delta"]?.jsonPrimitive?.content shouldBe "0.650000"
            greek["gamma"]?.jsonPrimitive?.content shouldBe "0.030000"
            greek["vega"]?.jsonPrimitive?.content shouldBe "125.400000"
            greek["theta"]?.jsonPrimitive?.content shouldBe "-8.200000"
            greek["rho"]?.jsonPrimitive?.content shouldBe "12.100000"
        }
    }

    test("GET returns null positionGreeks field when result has no option Greeks") {
        coEvery { riskClient.getLatestVaR("port-1", null) } returns sampleResult

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/risk/var/port-1")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["positionGreeks"]?.jsonNull shouldBe JsonNull
        }
    }

    // --- Regression ---

    test("GET /health still works with risk routes installed") {
        testApplication {
            application { module(riskClient) }
            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK
        }
    }
})
