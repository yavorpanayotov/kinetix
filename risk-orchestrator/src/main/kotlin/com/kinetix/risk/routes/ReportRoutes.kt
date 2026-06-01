package com.kinetix.risk.routes

import com.kinetix.risk.model.report.GenerateReportRequest
import com.kinetix.risk.model.report.ReportFormat
import com.kinetix.risk.model.report.ReportOutput
import com.kinetix.risk.model.report.ReportRowLimitExceededException
import com.kinetix.risk.model.report.ReportTemplate
import com.kinetix.risk.model.report.ReportTemplateNotFoundException
import com.kinetix.risk.routes.dtos.RecentReportResponse
import com.kinetix.risk.routes.dtos.ReportGenerateRequestBody
import com.kinetix.risk.routes.dtos.ReportOutputResponse
import com.kinetix.risk.routes.dtos.ReportTemplateResponse
import com.kinetix.risk.service.ReportService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ReportRoutes")

fun Route.reportRoutes(reportService: ReportService) {

    get("/api/v1/reports/templates") {
        val templates = reportService.listTemplates()
        call.respond(templates.map { it.toResponse() })
    }

    post("/api/v1/reports/generate") {
        val body = try {
            call.receive<ReportGenerateRequestBody>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request", "message" to "Malformed request body"))
            return@post
        }

        val templateId = body.templateId
        val bookId = body.bookId
        if (templateId.isNullOrBlank() || bookId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request", "message" to "templateId and bookId are required"))
            return@post
        }

        val format = runCatching { ReportFormat.valueOf(body.format) }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request", "message" to "Unknown format: ${body.format}"))
            return@post
        }

        val request = GenerateReportRequest(
            templateId = templateId,
            bookId = bookId,
            date = body.date,
            format = format,
        )

        try {
            val output = reportService.generateReport(request)
            call.respond(HttpStatusCode.OK, output.toResponse())
        } catch (e: ReportTemplateNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found", "message" to e.message))
        } catch (e: ReportRowLimitExceededException) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "row_limit_exceeded", "message" to e.message))
        } catch (e: Exception) {
            logger.error("Report generation failed for template {}", templateId, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to "Report generation failed"))
        }
    }

    // Registered before the `{outputId}` matcher so "recent" is served here
    // rather than being captured as a report identifier (the bug behind the
    // Reports tab's "Failed to fetch recent reports: 404").
    get("/api/v1/reports/recent") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()
        val recent = reportService.listRecentReports(limit).map { it.toRecentResponse() }
        call.respond(recent)
    }

    get("/api/v1/reports/{outputId}") {
        val outputId = call.parameters["outputId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val output = reportService.getOutput(outputId)
        if (output == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(output.toResponse())
        }
    }

    get("/api/v1/reports/{outputId}/csv") {
        val outputId = call.parameters["outputId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val output = reportService.getOutput(outputId)
        if (output == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val csv = output.toCsv()
        call.respondText(csv, ContentType.Text.CSV)
    }
}

private fun ReportTemplate.toResponse() = ReportTemplateResponse(
    templateId = templateId,
    name = name,
    templateType = templateType.name,
    ownerUserId = ownerUserId,
    description = definition.description,
    source = definition.source,
    columns = definition.columns,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun ReportOutput.toRecentResponse() = RecentReportResponse(
    outputId = outputId,
    templateId = templateId,
    timestamp = generatedAt.toString(),
    // Outputs aren't tagged with a principal yet; scheduled/system origin.
    user = "SYSTEM",
    // Generation is synchronous, so a persisted output is always complete.
    status = "COMPLETE",
    downloadUrl = "/api/v1/reports/$outputId/csv",
    rowCount = rowCount,
)

private fun ReportOutput.toResponse() = ReportOutputResponse(
    outputId = outputId,
    templateId = templateId,
    generatedAt = generatedAt.toString(),
    outputFormat = outputFormat.name,
    rowCount = rowCount,
    outputData = outputData,
)

private fun ReportOutput.toCsv(): String {
    val rows = outputData ?: return ""
    if (rows.isEmpty()) return ""

    val firstRow = rows.firstOrNull() as? JsonObject ?: return ""
    val headers = firstRow.keys.toList()

    val sb = StringBuilder()
    sb.appendLine(headers.joinToString(",") { escapeCsvCell(it) })
    for (element in rows) {
        val obj = element as? JsonObject ?: continue
        sb.appendLine(headers.joinToString(",") { key ->
            val value = obj[key]?.jsonPrimitive?.content ?: ""
            escapeCsvCell(value)
        })
    }
    return sb.toString().trimEnd()
}

private fun escapeCsvCell(value: String): String {
    return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }
}
