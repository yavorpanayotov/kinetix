package com.kinetix.gateway.routes

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class DemoResetStatus(
    val position: String,
    val audit: String,
    val risk: String,
    val price: String,
    val rates: String,
    val volatility: String,
    val correlation: String,
    val referenceData: String,
)

@Serializable
data class DemoResetErrorResponse(
    val errorCode: String,
    val message: String,
)

// In-process guard: a single gateway instance serializes demo resets and rejects
// concurrent requests with 409. The plan calls for a pg advisory lock pattern,
// but the gateway has no Postgres connection; an AtomicBoolean is the correct
// scope (one resetter at a time per gateway pod) and returns a 409 immediately
// instead of blocking, which is what the demo experience needs.
private val demoResetInProgress = AtomicBoolean(false)

data class DemoBackend(val name: String, val url: String, val path: String)

fun Route.demoAdminRoutes(
    httpClient: HttpClient,
    positionUrl: String,
    auditUrl: String,
    riskUrl: String,
    priceUrl: String,
    ratesUrl: String,
    volatilityUrl: String,
    correlationUrl: String,
    referenceDataUrl: String,
    adminKey: String,
    resetToken: String,
) {
    post("/api/v1/admin/demo-reset") {
        val key = call.request.headers["X-Demo-Admin-Key"]
        if (key != adminKey) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Invalid admin key"))
            return@post
        }

        if (!demoResetInProgress.compareAndSet(false, true)) {
            call.respond(
                HttpStatusCode.Conflict,
                DemoResetErrorResponse(
                    errorCode = "reset_in_progress",
                    message = "A demo reset is already in progress on this gateway.",
                ),
            )
            return@post
        }

        try {
            val backends = listOf(
                DemoBackend("position", positionUrl, "/api/v1/internal/position/demo-reset"),
                DemoBackend("audit", auditUrl, "/api/v1/internal/audit/demo-reset"),
                DemoBackend("risk", riskUrl, "/api/v1/internal/risk/demo-reset"),
                DemoBackend("price", priceUrl, "/api/v1/internal/price/demo-reset"),
                DemoBackend("rates", ratesUrl, "/api/v1/internal/rates/demo-reset"),
                DemoBackend("volatility", volatilityUrl, "/api/v1/internal/volatility/demo-reset"),
                DemoBackend("correlation", correlationUrl, "/api/v1/internal/correlation/demo-reset"),
                DemoBackend("referenceData", referenceDataUrl, "/api/v1/internal/reference-data/demo-reset"),
            )

            val results = backends.associate { backend ->
                backend.name to fanOutReset(httpClient, backend.url + backend.path, resetToken)
            }

            call.respond(
                DemoResetStatus(
                    position = results.getValue("position"),
                    audit = results.getValue("audit"),
                    risk = results.getValue("risk"),
                    price = results.getValue("price"),
                    rates = results.getValue("rates"),
                    volatility = results.getValue("volatility"),
                    correlation = results.getValue("correlation"),
                    referenceData = results.getValue("referenceData"),
                ),
            )
        } finally {
            demoResetInProgress.set(false)
        }
    }
}

private suspend fun fanOutReset(httpClient: HttpClient, url: String, resetToken: String): String {
    return try {
        val resp = httpClient.post(url) {
            header("X-Demo-Reset-Token", resetToken)
        }
        if (resp.status.isSuccess()) "ok" else "failed: ${resp.status}"
    } catch (e: Exception) {
        "error: ${e.message}"
    }
}
