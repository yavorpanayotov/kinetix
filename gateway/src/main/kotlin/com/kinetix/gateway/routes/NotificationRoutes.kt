package com.kinetix.gateway.routes

import com.kinetix.gateway.client.AcknowledgeAlertParams
import com.kinetix.gateway.client.AlertActionResult
import com.kinetix.gateway.client.EscalateAlertParams
import com.kinetix.gateway.client.NotificationServiceClient
import com.kinetix.gateway.client.ResolveAlertParams
import com.kinetix.gateway.dtos.AcknowledgeAlertRequest
import com.kinetix.gateway.dtos.CreateAlertRuleRequest
import com.kinetix.gateway.dtos.EscalateAlertRequest
import com.kinetix.gateway.dtos.ResolveAlertRequest
import com.kinetix.gateway.dtos.toDto
import com.kinetix.gateway.dtos.toParams
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.notificationRoutes(client: NotificationServiceClient) {
    route("/api/v1/notifications") {
        get("/rules", {
            summary = "List alert rules"
            tags = listOf("Notifications")
        }) {
            val rules = client.listRules()
            call.respond(rules.map { it.toDto() })
        }

        post("/rules", {
            summary = "Create alert rule"
            tags = listOf("Notifications")
        }) {
            val request = call.receive<CreateAlertRuleRequest>()
            val result = client.createRule(request.toParams())
            call.respond(HttpStatusCode.Created, result.toDto())
        }

        delete("/rules/{ruleId}", {
            summary = "Delete alert rule"
            tags = listOf("Notifications")
            request {
                pathParameter<String>("ruleId") { description = "Rule identifier" }
            }
        }) {
            val ruleId = call.requirePathParam("ruleId")
            val deleted = client.deleteRule(ruleId)
            if (deleted) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/alerts", {
            summary = "List alerts"
            tags = listOf("Notifications")
            request {
                queryParameter<Int>("limit") {
                    description = "Maximum number of alerts to return"
                    required = false
                }
                queryParameter<String>("status") {
                    description = "Filter by alert status (TRIGGERED, ACKNOWLEDGED, RESOLVED)"
                    required = false
                }
            }
        }) {
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 50
            val status = call.queryParameters["status"]
            val alerts = client.listAlerts(limit, status)
            call.respond(alerts.map { it.toDto() })
        }

        get("/alerts/escalated", {
            summary = "List escalated alerts"
            tags = listOf("Notifications")
        }) {
            val alerts = client.listEscalatedAlerts()
            call.respond(alerts.map { it.toDto() })
        }

        get("/alerts/{alertId}/contributors", {
            summary = "Get alert contributors"
            tags = listOf("Notifications")
            request {
                pathParameter<String>("alertId") { description = "Alert event identifier" }
            }
        }) {
            val alertId = call.requirePathParam("alertId")
            val json = client.getAlertContributors(alertId)
            if (json != null) {
                call.respondText(json, ContentType.Application.Json)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        post("/alerts/{alertId}/acknowledge", {
            summary = "Acknowledge an alert"
            tags = listOf("Notifications")
            request {
                pathParameter<String>("alertId") { description = "Alert event identifier" }
                body<AcknowledgeAlertRequest>()
            }
        }) {
            val alertId = call.requirePathParam("alertId")
            val request = call.receive<AcknowledgeAlertRequest>()
            val result = client.acknowledgeAlert(
                alertId,
                AcknowledgeAlertParams(
                    acknowledgedBy = request.acknowledgedBy,
                    notes = request.notes,
                ),
            )
            if (result != null) {
                call.respond(result.toDto())
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        post("/alerts/{alertId}/escalate", {
            summary = "Manually escalate an alert"
            tags = listOf("Notifications")
            request {
                pathParameter<String>("alertId") { description = "Alert event identifier" }
                body<EscalateAlertRequest>()
            }
        }) {
            val alertId = call.requirePathParam("alertId")
            val request = call.receive<EscalateAlertRequest>()
            val result = client.escalateAlert(
                alertId,
                EscalateAlertParams(reason = request.reason, assignee = request.assignee),
            )
            call.respondToAlertAction(result)
        }

        post("/alerts/{alertId}/resolve", {
            summary = "Resolve an alert"
            tags = listOf("Notifications")
            request {
                pathParameter<String>("alertId") { description = "Alert event identifier" }
                body<ResolveAlertRequest>()
            }
        }) {
            val alertId = call.requirePathParam("alertId")
            val request = call.receive<ResolveAlertRequest>()
            val result = client.resolveAlert(
                alertId,
                ResolveAlertParams(resolutionText = request.resolutionText),
            )
            call.respondToAlertAction(result)
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondToAlertAction(result: AlertActionResult) {
    when (result) {
        is AlertActionResult.Ok -> respond(result.alert.toDto())
        is AlertActionResult.NotFound -> respond(HttpStatusCode.NotFound)
        is AlertActionResult.BadRequest -> respond(HttpStatusCode.BadRequest, result.message)
        is AlertActionResult.Conflict -> respond(HttpStatusCode.Conflict, result.message)
    }
}
