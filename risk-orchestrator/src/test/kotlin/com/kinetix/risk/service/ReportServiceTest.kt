package com.kinetix.risk.service

import com.kinetix.risk.model.report.ReportTemplate
import com.kinetix.risk.model.report.ReportTemplateType
import com.kinetix.risk.model.report.ReportOutput
import com.kinetix.risk.model.report.ReportFormat
import com.kinetix.risk.model.report.ReportDefinition
import com.kinetix.risk.model.report.GenerateReportRequest
import com.kinetix.risk.model.report.ReportTemplateNotFoundException
import com.kinetix.risk.persistence.ReportRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

private val RISK_SUMMARY_TEMPLATE = ReportTemplate(
    templateId = "tpl-risk-summary",
    name = "Risk Summary",
    templateType = ReportTemplateType.RISK_SUMMARY,
    ownerUserId = "SYSTEM",
    definition = ReportDefinition(
        description = "Per-book VaR, ES, top 5 positions by VaR contribution, aggregate Greeks",
        source = "risk_positions_flat",
        columns = listOf("book_id", "instrument_id", "asset_class", "var_contribution"),
    ),
    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
)

private val PNL_ATTRIBUTION_TEMPLATE = ReportTemplate(
    templateId = "tpl-pnl-attribution",
    name = "P&L Attribution",
    templateType = ReportTemplateType.PNL_ATTRIBUTION,
    ownerUserId = "SYSTEM",
    definition = ReportDefinition(
        description = "Daily P&L decomposition by Greek factor",
        source = "pnl_attributions",
        columns = listOf("book_id", "attribution_date", "total_pnl", "delta_pnl"),
    ),
    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
)

class ReportServiceTest : FunSpec({

    val repository = mockk<ReportRepository>()
    val queryExecutor = mockk<ReportQueryExecutor>()
    val service = ReportService(repository, queryExecutor)

    test("listTemplates returns all templates from repository") {
        val templates = listOf(RISK_SUMMARY_TEMPLATE, PNL_ATTRIBUTION_TEMPLATE)
        coEvery { repository.listTemplates() } returns templates

        val result = service.listTemplates()

        result shouldBe templates
    }

    test("listRecentReports delegates to the repository with the default limit when none given") {
        val outputs = listOf(
            ReportOutput("o2", "tpl-risk-summary", Instant.parse("2025-01-16T00:00:00Z"), ReportFormat.JSON, 3, null),
            ReportOutput("o1", "tpl-pnl-attribution", Instant.parse("2025-01-15T00:00:00Z"), ReportFormat.JSON, 1, null),
        )
        coEvery { repository.listRecentOutputs(20) } returns outputs

        val result = service.listRecentReports(null)

        result shouldBe outputs
        coVerify { repository.listRecentOutputs(20) }
    }

    test("listRecentReports clamps the requested limit to a sane maximum") {
        coEvery { repository.listRecentOutputs(100) } returns emptyList()

        service.listRecentReports(10_000)

        coVerify { repository.listRecentOutputs(100) }
    }

    test("listRecentReports floors a non-positive limit to the default") {
        coEvery { repository.listRecentOutputs(20) } returns emptyList()

        service.listRecentReports(0)

        coVerify { repository.listRecentOutputs(20) }
    }

    test("generateReport saves output and returns it when rows are within limit") {
        val rows = buildJsonArray {
            add(buildJsonObject {
                put("book_id", "BOOK-1")
                put("instrument_id", "AAPL")
                put("asset_class", "EQUITY")
                put("var_contribution", "1500.00")
            })
            add(buildJsonObject {
                put("book_id", "BOOK-1")
                put("instrument_id", "GOOGL")
                put("asset_class", "EQUITY")
                put("var_contribution", "800.00")
            })
        }

        coEvery { repository.findTemplate("tpl-risk-summary") } returns RISK_SUMMARY_TEMPLATE
        coEvery { queryExecutor.executeRiskSummary("BOOK-1", null) } returns rows
        val savedSlot = slot<ReportOutput>()
        coEvery { repository.saveOutput(capture(savedSlot)) } returns Unit

        val request = GenerateReportRequest(
            templateId = "tpl-risk-summary",
            bookId = "BOOK-1",
            date = null,
            format = ReportFormat.JSON,
        )
        val result = service.generateReport(request)

        result.templateId shouldBe "tpl-risk-summary"
        result.rowCount shouldBe 2
        result.outputFormat shouldBe ReportFormat.JSON
        result.outputData shouldNotBe null

        coVerify(exactly = 1) { repository.saveOutput(any()) }
        savedSlot.captured.rowCount shouldBe 2
    }

    test("generateReport rejects when estimated row count exceeds 100000") {
        val hugeRows = buildJsonArray {
            repeat(100_001) {
                add(buildJsonObject { put("book_id", "BOOK-1") })
            }
        }

        coEvery { repository.findTemplate("tpl-risk-summary") } returns RISK_SUMMARY_TEMPLATE
        coEvery { queryExecutor.executeRiskSummary("BOOK-1", null) } returns hugeRows

        val request = GenerateReportRequest(
            templateId = "tpl-risk-summary",
            bookId = "BOOK-1",
            date = null,
            format = ReportFormat.JSON,
        )

        val exception = runCatching { service.generateReport(request) }.exceptionOrNull()
        exception shouldNotBe null
        exception!!.message shouldContain "exceeds"
    }

    test("generateReport returns 404-style exception when template not found") {
        coEvery { repository.findTemplate("nonexistent-template") } returns null

        val request = GenerateReportRequest(
            templateId = "nonexistent-template",
            bookId = "BOOK-1",
            date = null,
            format = ReportFormat.JSON,
        )

        val exception = runCatching { service.generateReport(request) }.exceptionOrNull()
        exception shouldNotBe null
        (exception is ReportTemplateNotFoundException) shouldBe true
    }

    test("getOutput returns output when found") {
        val output = ReportOutput(
            outputId = "out-123",
            templateId = "tpl-risk-summary",
            generatedAt = Instant.now(),
            outputFormat = ReportFormat.JSON,
            rowCount = 2,
            outputData = buildJsonArray {},
        )
        coEvery { repository.findOutput("out-123") } returns output

        val result = service.getOutput("out-123")

        result shouldBe output
    }

    test("getOutput returns null when not found") {
        coEvery { repository.findOutput("missing") } returns null

        val result = service.getOutput("missing")

        result shouldBe null
    }

    test("generateReport for PNL_ATTRIBUTION template delegates to PnL attribution query executor") {
        val rows = buildJsonArray {
            add(buildJsonObject {
                put("book_id", "BOOK-1")
                put("attribution_date", "2025-01-15")
                put("total_pnl", "5000.00")
                put("delta_pnl", "3000.00")
            })
        }

        coEvery { repository.findTemplate("tpl-pnl-attribution") } returns PNL_ATTRIBUTION_TEMPLATE
        coEvery { queryExecutor.executePnlAttribution("BOOK-1", null) } returns rows
        coEvery { repository.saveOutput(any()) } returns Unit

        val request = GenerateReportRequest(
            templateId = "tpl-pnl-attribution",
            bookId = "BOOK-1",
            date = null,
            format = ReportFormat.JSON,
        )
        val result = service.generateReport(request)

        result.rowCount shouldBe 1
        coVerify(exactly = 1) { queryExecutor.executePnlAttribution("BOOK-1", null) }
    }

    test("generateReport with date filter passes date to query executor") {
        val rows = buildJsonArray {
            add(buildJsonObject { put("book_id", "BOOK-1") })
        }

        coEvery { repository.findTemplate("tpl-pnl-attribution") } returns PNL_ATTRIBUTION_TEMPLATE
        coEvery { queryExecutor.executePnlAttribution("BOOK-1", "2025-01-15") } returns rows
        coEvery { repository.saveOutput(any()) } returns Unit

        val request = GenerateReportRequest(
            templateId = "tpl-pnl-attribution",
            bookId = "BOOK-1",
            date = "2025-01-15",
            format = ReportFormat.JSON,
        )
        service.generateReport(request)

        coVerify(exactly = 1) { queryExecutor.executePnlAttribution("BOOK-1", "2025-01-15") }
    }
})
