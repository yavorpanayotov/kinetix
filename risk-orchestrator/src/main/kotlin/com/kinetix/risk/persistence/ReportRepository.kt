package com.kinetix.risk.persistence

import com.kinetix.risk.model.report.ReportOutput
import com.kinetix.risk.model.report.ReportTemplate

interface ReportRepository {
    suspend fun listTemplates(): List<ReportTemplate>
    suspend fun findTemplate(templateId: String): ReportTemplate?
    suspend fun saveOutput(output: ReportOutput)
    suspend fun findOutput(outputId: String): ReportOutput?

    /**
     * The most recently generated outputs, newest first, capped at [limit].
     * Backs the Reports tab "Recent Reports" panel.
     */
    suspend fun listRecentOutputs(limit: Int): List<ReportOutput>
}
