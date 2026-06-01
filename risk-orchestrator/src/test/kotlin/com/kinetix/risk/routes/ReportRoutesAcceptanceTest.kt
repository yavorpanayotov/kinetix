package com.kinetix.risk.routes

import com.kinetix.risk.model.report.GenerateReportRequest
import com.kinetix.risk.model.report.ReportDefinition
import com.kinetix.risk.model.report.ReportFormat
import com.kinetix.risk.model.report.ReportOutput
import com.kinetix.risk.model.report.ReportTemplate
import com.kinetix.risk.model.report.ReportTemplateNotFoundException
import com.kinetix.risk.model.report.ReportTemplateType
import com.kinetix.risk.model.report.ReportRowLimitExceededException
import com.kinetix.risk.service.ReportService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

private val RISK_TEMPLATE = ReportTemplate(
    templateId = "tpl-risk-summary",
    name = "Risk Summary",
    templateType = ReportTemplateType.RISK_SUMMARY,
    ownerUserId = "SYSTEM",
    definition = ReportDefinition(
        description = "Per-book VaR and Greeks",
        source = "risk_positions_flat",
        columns = listOf("book_id", "instrument_id", "var_contribution"),
    ),
    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
)

private val SAMPLE_OUTPUT = ReportOutput(
    outputId = "out-abc",
    templateId = "tpl-risk-summary",
    generatedAt = Instant.parse("2025-01-15T10:00:00Z"),
    outputFormat = ReportFormat.JSON,
    rowCount = 2,
    outputData = buildJsonArray {
        add(buildJsonObject {
            put("book_id", "BOOK-1")
            put("instrument_id", "AAPL")
            put("var_contribution", "1500.00")
        })
        add(buildJsonObject {
            put("book_id", "BOOK-1")
            put("instrument_id", "GOOGL")
            put("var_contribution", "800.00")
        })
    },
)

class ReportRoutesAcceptanceTest : FunSpec({

    val reportService = mockk<ReportService>()

    beforeEach {
        clearMocks(reportService)
    }

    test("GET /api/v1/reports/templates returns list of available templates") {
        coEvery { reportService.listTemplates() } returns listOf(RISK_TEMPLATE)

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.get("/api/v1/reports/templates")
            response.status shouldBe HttpStatusCode.OK

            val body = response.bodyAsText()
            body shouldContain "tpl-risk-summary"
            body shouldContain "Risk Summary"
            body shouldContain "RISK_SUMMARY"
        }
    }

    test("GET /api/v1/reports/templates returns empty array when no templates") {
        coEvery { reportService.listTemplates() } returns emptyList()

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.get("/api/v1/reports/templates")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "[]"
        }
    }

    test("POST /api/v1/reports/generate returns 200 with output when generation succeeds") {
        coEvery {
            reportService.generateReport(
                GenerateReportRequest(
                    templateId = "tpl-risk-summary",
                    bookId = "BOOK-1",
                    date = null,
                    format = ReportFormat.JSON,
                )
            )
        } returns SAMPLE_OUTPUT

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.post("/api/v1/reports/generate") {
                contentType(ContentType.Application.Json)
                setBody("""{"templateId":"tpl-risk-summary","bookId":"BOOK-1","format":"JSON"}""")
            }
            response.status shouldBe HttpStatusCode.OK

            val body = response.bodyAsText()
            body shouldContain "out-abc"
            body shouldContain "tpl-risk-summary"
            body shouldContain "rowCount"
        }
    }

    test("POST /api/v1/reports/generate returns 404 when template not found") {
        coEvery {
            reportService.generateReport(any())
        } throws ReportTemplateNotFoundException("nonexistent")

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.post("/api/v1/reports/generate") {
                contentType(ContentType.Application.Json)
                setBody("""{"templateId":"nonexistent","bookId":"BOOK-1","format":"JSON"}""")
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST /api/v1/reports/generate returns 422 when row count exceeds limit") {
        coEvery {
            reportService.generateReport(any())
        } throws ReportRowLimitExceededException(100_001, 100_000)

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.post("/api/v1/reports/generate") {
                contentType(ContentType.Application.Json)
                setBody("""{"templateId":"tpl-risk-summary","bookId":"BOOK-1","format":"JSON"}""")
            }
            response.status shouldBe HttpStatusCode.UnprocessableEntity
            response.bodyAsText() shouldContain "exceeds"
        }
    }

    test("GET /api/v1/reports/{outputId} returns the generated output") {
        coEvery { reportService.getOutput("out-abc") } returns SAMPLE_OUTPUT

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.get("/api/v1/reports/out-abc")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "out-abc"
        }
    }

    test("GET /api/v1/reports/{outputId} returns 404 when not found") {
        coEvery { reportService.getOutput("missing") } returns null

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.get("/api/v1/reports/missing")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /api/v1/reports/{outputId}/csv returns CSV text when output found") {
        coEvery { reportService.getOutput("out-abc") } returns SAMPLE_OUTPUT

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.get("/api/v1/reports/out-abc/csv")
            response.status shouldBe HttpStatusCode.OK
            response.contentType()?.contentType shouldBe "text"
            response.contentType()?.contentSubtype shouldBe "csv"

            val csv = response.bodyAsText()
            csv shouldContain "book_id"
            csv shouldContain "BOOK-1"
            csv shouldContain "AAPL"
        }
    }

    test("GET /api/v1/reports/{outputId}/csv returns 404 when output not found") {
        coEvery { reportService.getOutput("missing") } returns null

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.get("/api/v1/reports/missing/csv")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /api/v1/reports/recent returns reverse-chronological rows with the UI contract fields") {
        val newer = SAMPLE_OUTPUT.copy(
            outputId = "out-new",
            generatedAt = Instant.parse("2025-01-16T12:00:00Z"),
            rowCount = 42,
        )
        val older = SAMPLE_OUTPUT.copy(
            outputId = "out-old",
            templateId = "tpl-pnl-attribution",
            generatedAt = Instant.parse("2025-01-15T09:00:00Z"),
            rowCount = 7,
        )
        // Service returns rows already ordered newest-first (repository ORDER BY).
        coEvery { reportService.listRecentReports(null) } returns listOf(newer, older)

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.get("/api/v1/reports/recent")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 2
            body[0].jsonObject["outputId"]?.jsonPrimitive?.content shouldBe "out-new"
            body[1].jsonObject["outputId"]?.jsonPrimitive?.content shouldBe "out-old"

            val first = body[0].jsonObject
            first["templateId"]?.jsonPrimitive?.content shouldBe "tpl-risk-summary"
            first["timestamp"]?.jsonPrimitive?.content shouldBe "2025-01-16T12:00:00Z"
            first["user"]?.jsonPrimitive?.content shouldBe "SYSTEM"
            first["status"]?.jsonPrimitive?.content shouldBe "COMPLETE"
            first["downloadUrl"]?.jsonPrimitive?.content shouldBe "/api/v1/reports/out-new/csv"
            first["rowCount"]?.jsonPrimitive?.content shouldBe "42"
        }
    }

    test("GET /api/v1/reports/recent returns an empty array when no reports have been generated") {
        coEvery { reportService.listRecentReports(null) } returns emptyList()

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.get("/api/v1/reports/recent")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "[]"
        }
    }

    test("GET /api/v1/reports/recent forwards the limit query parameter to the service") {
        coEvery { reportService.listRecentReports(5) } returns emptyList()

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.get("/api/v1/reports/recent?limit=5")
            response.status shouldBe HttpStatusCode.OK
            coVerify { reportService.listRecentReports(5) }
        }
    }

    test("GET /api/v1/reports/recent is matched before the {outputId} route, not treated as an outputId") {
        coEvery { reportService.listRecentReports(null) } returns emptyList()

        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.get("/api/v1/reports/recent")
            response.status shouldBe HttpStatusCode.OK
            // The {outputId} handler must never see "recent" as an id.
            coVerify(exactly = 0) { reportService.getOutput("recent") }
        }
    }

    test("POST /api/v1/reports/generate returns 400 when request body is missing templateId") {
        testApplication {
            install(ContentNegotiation) { json() }
            routing { reportRoutes(reportService) }

            val response = client.post("/api/v1/reports/generate") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"BOOK-1","format":"JSON"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }
})
