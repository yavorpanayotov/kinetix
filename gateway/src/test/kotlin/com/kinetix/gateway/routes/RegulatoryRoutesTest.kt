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

private val sampleFrtbResult = FrtbResultSummary(
    bookId = "port-1",
    sbmCharges = listOf(
        RiskClassChargeItem(
            riskClass = "EQUITY",
            deltaCharge = 40000.0,
            vegaCharge = 30000.0,
            curvatureCharge = 4000.0,
            totalCharge = 74000.0,
        ),
        RiskClassChargeItem(
            riskClass = "GIRR",
            deltaCharge = 1500.0,
            vegaCharge = 1000.0,
            curvatureCharge = 112.5,
            totalCharge = 2612.5,
        ),
    ),
    totalSbmCharge = 76612.5,
    grossJtd = 5400.0,
    hedgeBenefit = 0.0,
    netDrc = 5400.0,
    exoticNotional = 400000.0,
    otherNotional = 1700000.0,
    totalRrao = 5700.0,
    totalCapitalCharge = 87712.5,
    calculatedAt = Instant.parse("2025-01-15T10:00:00Z"),
)

private val sampleReportResult = ReportResult(
    bookId = "port-1",
    format = "CSV",
    content = "Component,Risk Class,Delta Charge\nSbM,EQUITY,40000.00",
    generatedAt = Instant.parse("2025-01-15T10:00:00Z"),
)

class RegulatoryRoutesTest : FunSpec({

    val riskClient = mockk<RiskServiceClient>()

    beforeEach {
        clearMocks(riskClient)
        coEvery { riskClient.calculateVaR(any()) } returns null
        coEvery { riskClient.getLatestVaR(any()) } returns null
        coEvery { riskClient.runStressTest(any()) } returns null
        coEvery { riskClient.listScenarios() } returns emptyList()
        coEvery { riskClient.calculateGreeks(any()) } returns null
    }

    test("POST /api/v1/regulatory/frtb/{bookId} returns capital charge") {
        coEvery { riskClient.calculateFrtb(any()) } returns sampleFrtbResult

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/regulatory/frtb/port-1") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
            body["totalCapitalCharge"]?.jsonPrimitive?.content shouldBe "87712.50"
            body["totalSbmCharge"]?.jsonPrimitive?.content shouldBe "76612.50"
            val sbmCharges = body["sbmCharges"]?.jsonArray
            sbmCharges?.size shouldBe 2
            sbmCharges!![0].jsonObject["riskClass"]?.jsonPrimitive?.content shouldBe "EQUITY"
        }
    }

    test("GET /api/v1/regulatory/frtb/{bookId}/latest returns the most recent calculation") {
        coEvery { riskClient.getLatestFrtb("port-1") } returns sampleFrtbResult

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/regulatory/frtb/port-1/latest")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
            body["totalCapitalCharge"]?.jsonPrimitive?.content shouldBe "87712.50"
        }
    }

    test("GET /api/v1/regulatory/frtb/{bookId}/latest returns 404 when no calculation exists") {
        coEvery { riskClient.getLatestFrtb("port-1") } returns null

        testApplication {
            application { module(riskClient) }
            val response = client.get("/api/v1/regulatory/frtb/port-1/latest")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST /api/v1/regulatory/report/{bookId} CSV returns content") {
        coEvery { riskClient.generateReport(any(), any()) } returns sampleReportResult

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/regulatory/report/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"format":"CSV"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
            body["format"]?.jsonPrimitive?.content shouldBe "CSV"
            body["content"]?.jsonPrimitive?.content?.contains("Component") shouldBe true
        }
    }

    test("POST /api/v1/regulatory/report/{bookId} XBRL returns content") {
        val xbrlResult = sampleReportResult.copy(
            format = "XBRL",
            content = """<?xml version='1.0' encoding='utf-8'?><FRTBReport bookId="port-1"/>""",
        )
        coEvery { riskClient.generateReport(any(), any()) } returns xbrlResult

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/regulatory/report/port-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"format":"XBRL"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["format"]?.jsonPrimitive?.content shouldBe "XBRL"
            body["content"]?.jsonPrimitive?.content?.contains("FRTBReport") shouldBe true
        }
    }

    test("POST /api/v1/regulatory/frtb/{bookId} returns 404 when not found") {
        coEvery { riskClient.calculateFrtb(any()) } returns null

        testApplication {
            application { module(riskClient) }
            val response = client.post("/api/v1/regulatory/frtb/port-1") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})
