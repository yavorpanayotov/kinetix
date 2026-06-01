package com.kinetix.risk.persistence

import com.kinetix.risk.model.report.ReportDefinition
import com.kinetix.risk.model.report.ReportFormat
import com.kinetix.risk.model.report.ReportOutput
import com.kinetix.risk.model.report.ReportTemplate
import com.kinetix.risk.model.report.ReportTemplateType
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedReportRepository(private val db: Database? = null) : ReportRepository {

    override suspend fun listTemplates(): List<ReportTemplate> =
        newSuspendedTransaction(db = db) {
            ReportTemplatesTable
                .selectAll()
                .map { it.toTemplate() }
        }

    override suspend fun findTemplate(templateId: String): ReportTemplate? =
        newSuspendedTransaction(db = db) {
            ReportTemplatesTable
                .selectAll()
                .where { ReportTemplatesTable.templateId eq templateId }
                .firstOrNull()
                ?.toTemplate()
        }

    override suspend fun saveOutput(output: ReportOutput): Unit =
        newSuspendedTransaction(db = db) {
            ReportOutputsTable.insert {
                it[outputId] = output.outputId
                it[templateId] = output.templateId
                it[generatedAt] = OffsetDateTime.ofInstant(output.generatedAt, ZoneOffset.UTC)
                it[outputFormat] = output.outputFormat.name
                it[rowCount] = output.rowCount
                it[outputData] = output.outputData
            }
        }

    override suspend fun findOutput(outputId: String): ReportOutput? =
        newSuspendedTransaction(db = db) {
            ReportOutputsTable
                .selectAll()
                .where { ReportOutputsTable.outputId eq outputId }
                .firstOrNull()
                ?.toOutput()
        }

    override suspend fun listRecentOutputs(limit: Int): List<ReportOutput> =
        newSuspendedTransaction(db = db) {
            ReportOutputsTable
                .selectAll()
                .orderBy(ReportOutputsTable.generatedAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toOutput() }
        }

    private fun ResultRow.toTemplate() = ReportTemplate(
        templateId = this[ReportTemplatesTable.templateId],
        name = this[ReportTemplatesTable.name],
        templateType = runCatching {
            ReportTemplateType.valueOf(this[ReportTemplatesTable.templateType])
        }.getOrDefault(ReportTemplateType.RISK_SUMMARY),
        ownerUserId = this[ReportTemplatesTable.ownerUserId],
        definition = this[ReportTemplatesTable.definition].let { json ->
            ReportDefinition(
                description = json.description,
                source = json.source,
                columns = json.columns,
            )
        },
        createdAt = this[ReportTemplatesTable.createdAt].toInstant(),
        updatedAt = this[ReportTemplatesTable.updatedAt].toInstant(),
    )

    private fun ResultRow.toOutput() = ReportOutput(
        outputId = this[ReportOutputsTable.outputId],
        templateId = this[ReportOutputsTable.templateId],
        generatedAt = this[ReportOutputsTable.generatedAt].toInstant(),
        outputFormat = runCatching {
            ReportFormat.valueOf(this[ReportOutputsTable.outputFormat])
        }.getOrDefault(ReportFormat.JSON),
        rowCount = this[ReportOutputsTable.rowCount],
        outputData = this[ReportOutputsTable.outputData],
    )
}
