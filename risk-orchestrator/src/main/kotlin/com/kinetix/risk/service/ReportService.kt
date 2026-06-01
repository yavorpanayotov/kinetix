package com.kinetix.risk.service

import com.kinetix.risk.model.report.GenerateReportRequest
import com.kinetix.risk.model.report.ReportOutput
import com.kinetix.risk.model.report.ReportRowLimitExceededException
import com.kinetix.risk.model.report.ReportTemplate
import com.kinetix.risk.model.report.ReportTemplateNotFoundException
import com.kinetix.risk.model.report.ReportTemplateType
import com.kinetix.risk.persistence.ReportRepository
import java.time.Instant
import java.util.UUID

private const val MAX_ROWS = 100_000
private const val DEFAULT_RECENT_LIMIT = 20
private const val MAX_RECENT_LIMIT = 100

class ReportService(
    private val repository: ReportRepository,
    private val queryExecutor: ReportQueryExecutor,
) {

    suspend fun listTemplates(): List<ReportTemplate> =
        repository.listTemplates()

    /**
     * Most recently generated outputs, newest first, backing the Reports tab
     * "Recent Reports" panel. A null, zero, or negative [limit] falls back to
     * [DEFAULT_RECENT_LIMIT]; requests above [MAX_RECENT_LIMIT] are clamped so a
     * caller can't pull the whole table.
     */
    suspend fun listRecentReports(limit: Int?): List<ReportOutput> {
        val effective = when {
            limit == null || limit <= 0 -> DEFAULT_RECENT_LIMIT
            limit > MAX_RECENT_LIMIT -> MAX_RECENT_LIMIT
            else -> limit
        }
        return repository.listRecentOutputs(effective)
    }

    suspend fun generateReport(request: GenerateReportRequest): ReportOutput {
        val template = repository.findTemplate(request.templateId)
            ?: throw ReportTemplateNotFoundException(request.templateId)

        val rows = when (template.templateType) {
            ReportTemplateType.RISK_SUMMARY ->
                queryExecutor.executeRiskSummary(request.bookId, request.date)
            ReportTemplateType.STRESS_TEST_SUMMARY ->
                queryExecutor.executeStressTestSummary(request.bookId, request.date)
            ReportTemplateType.PNL_ATTRIBUTION ->
                queryExecutor.executePnlAttribution(request.bookId, request.date)
        }

        if (rows.size > MAX_ROWS) {
            throw ReportRowLimitExceededException(rows.size, MAX_ROWS)
        }

        val output = ReportOutput(
            outputId = UUID.randomUUID().toString(),
            templateId = template.templateId,
            generatedAt = Instant.now(),
            outputFormat = request.format,
            rowCount = rows.size,
            outputData = rows,
        )

        repository.saveOutput(output)
        return output
    }

    suspend fun getOutput(outputId: String): ReportOutput? =
        repository.findOutput(outputId)
}
