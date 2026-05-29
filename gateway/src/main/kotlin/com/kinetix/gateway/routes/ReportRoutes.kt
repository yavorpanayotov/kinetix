package com.kinetix.gateway.routes

import com.kinetix.gateway.client.RiskServiceClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

fun Route.reportProxyRoutes(riskClient: RiskServiceClient) {

    get("/api/v1/reports/templates") {
        val templates = riskClient.listReportTemplates()
        call.respond(templates)
    }

    post("/api/v1/reports/generate") {
        val body = call.receive<JsonObject>()
        val result = riskClient.generateReport(body)
        call.respond(result)
    }

    // Trader-review P2 #24 — recent reports list (last N generated reports with
    // status). Registered before the `{outputId}` matcher so "recent" is not
    // captured as a report identifier. Forwards an optional `limit` query param.
    get("/api/v1/reports/recent") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()
        val recent = riskClient.getRecentReports(limit)
        call.respond(recent)
    }

    get("/api/v1/reports/{outputId}") {
        val outputId = call.parameters["outputId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val output = riskClient.getReportOutput(outputId)
        if (output == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(output)
        }
    }

    get("/api/v1/reports/{outputId}/csv") {
        val outputId = call.parameters["outputId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val csv = riskClient.getReportOutputCsv(outputId)
        if (csv == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respondText(csv, ContentType.Text.CSV)
        }
    }
}
