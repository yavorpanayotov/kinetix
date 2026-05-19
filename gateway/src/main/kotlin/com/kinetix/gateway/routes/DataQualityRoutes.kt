package com.kinetix.gateway.routes

import com.kinetix.gateway.dtos.DataQualityCheckResponse
import com.kinetix.gateway.dtos.DataQualityStatusResponse
import io.github.smiley4.ktoropenapi.get
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
private data class FIXSessionDto(
    val sessionId: String,
    val counterparty: String,
    val status: String,
    val lastMessageAt: String? = null,
    val inboundSeqNum: Int,
    val outboundSeqNum: Int,
)

@Serializable
private data class StaleInstrumentDto(
    val instrumentId: String,
    val lastUpdated: String,
    val ageHours: Long,
    val status: String,
)

@Serializable
private data class ReadinessResponseDto(
    val status: String,
    val checks: Map<String, CheckResultDto> = emptyMap(),
    val consumers: Map<String, ConsumerHealthDto> = emptyMap(),
)

@Serializable
private data class CheckResultDto(
    val status: String,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
private data class ConsumerHealthDto(
    val lastProcessedAt: String?,
    val recordsProcessedTotal: Long,
    val recordsSentToDlqTotal: Long,
    val lastErrorAt: String?,
    val consecutiveErrorCount: Long,
)

@Serializable
private data class PortfolioListItemDto(val bookId: String)

fun Route.dataQualityRoutes(
    httpClient: HttpClient? = null,
    positionUrl: String? = null,
    priceUrl: String? = null,
    riskUrl: String? = null,
) {
    route("/api/v1/data-quality") {
        get("/status", {
            summary = "Get data quality status"
            tags = listOf("Data Quality")
        }) {
            val now = Instant.now().toString()

            val priceFreshnessCheck = buildPriceFreshnessCheck(httpClient, priceUrl, now)
            val positionCountCheck = buildPositionCountCheck(httpClient, positionUrl, now)
            val riskCompletenessCheck = buildRiskCompletenessCheck(httpClient, riskUrl, now)
            val fixSessionCheck = buildFixSessionCheck(httpClient, positionUrl, now)

            val checks = listOf(
                priceFreshnessCheck,
                positionCountCheck,
                riskCompletenessCheck,
                fixSessionCheck,
            )

            val overall = when {
                checks.any { it.status == "CRITICAL" } -> "CRITICAL"
                checks.any { it.status == "WARNING" } -> "WARNING"
                checks.all { it.status == "UNKNOWN" } -> "UNKNOWN"
                else -> "OK"
            }

            call.respond(DataQualityStatusResponse(overall = overall, checks = checks))
        }
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

private suspend fun buildPriceFreshnessCheck(
    httpClient: HttpClient?,
    priceUrl: String?,
    now: String,
): DataQualityCheckResponse {
    if (httpClient == null || priceUrl == null) {
        return DataQualityCheckResponse(
            name = "Price Freshness",
            status = "UNKNOWN",
            message = "not configured",
            lastChecked = now,
        )
    }

    return try {
        val readyResponse = httpClient.get("$priceUrl/health/ready")
        if (!readyResponse.status.isSuccess()) {
            return DataQualityCheckResponse(
                name = "Price Freshness",
                status = "CRITICAL",
                message = "Price service reported status ${readyResponse.status.value}",
                lastChecked = now,
            )
        }
        val stale = try {
            val staleResponse = httpClient.get("$priceUrl/api/v1/prices/stale")
            if (staleResponse.status.isSuccess()) {
                lenientJson.decodeFromString<List<StaleInstrumentDto>>(staleResponse.body())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        if (stale.isEmpty()) {
            DataQualityCheckResponse(
                name = "Price Freshness",
                status = "OK",
                message = "Price service is ready",
                lastChecked = now,
            )
        } else {
            val sample = stale.first()
            DataQualityCheckResponse(
                name = "Price Freshness",
                status = "WARNING",
                message = "${stale.size} stale instrument(s); ${sample.instrumentId} last updated ${sample.ageHours}h ago",
                lastChecked = now,
            )
        }
    } catch (e: Exception) {
        DataQualityCheckResponse(
            name = "Price Freshness",
            status = "CRITICAL",
            message = "Cannot reach price-service: ${e.message}",
            lastChecked = now,
        )
    }
}

private suspend fun buildPositionCountCheck(
    httpClient: HttpClient?,
    positionUrl: String?,
    now: String,
): DataQualityCheckResponse {
    if (httpClient == null || positionUrl == null) {
        return DataQualityCheckResponse(
            name = "Position Count",
            status = "UNKNOWN",
            message = "not configured",
            lastChecked = now,
        )
    }

    return try {
        val response = httpClient.get("$positionUrl/api/v1/books")
        if (!response.status.isSuccess()) {
            return DataQualityCheckResponse(
                name = "Position Count",
                status = "CRITICAL",
                message = "Failed to fetch portfolios: ${response.status.value}",
                lastChecked = now,
            )
        }
        val portfolios = lenientJson.decodeFromString<List<PortfolioListItemDto>>(response.body())
        if (portfolios.isNotEmpty()) {
            DataQualityCheckResponse(
                name = "Position Count",
                status = "OK",
                message = "${portfolios.size} portfolio(s) active",
                lastChecked = now,
            )
        } else {
            DataQualityCheckResponse(
                name = "Position Count",
                status = "WARNING",
                message = "No portfolios found — position data may be absent",
                lastChecked = now,
            )
        }
    } catch (e: Exception) {
        DataQualityCheckResponse(
            name = "Position Count",
            status = "CRITICAL",
            message = "Cannot reach position-service: ${e.message}",
            lastChecked = now,
        )
    }
}

private suspend fun buildRiskCompletenessCheck(
    httpClient: HttpClient?,
    riskUrl: String?,
    now: String,
): DataQualityCheckResponse {
    if (httpClient == null || riskUrl == null) {
        return DataQualityCheckResponse(
            name = "Risk Result Completeness",
            status = "UNKNOWN",
            message = "not configured",
            lastChecked = now,
        )
    }

    return try {
        val response = httpClient.get("$riskUrl/health/ready")
        if (!response.status.isSuccess()) {
            return DataQualityCheckResponse(
                name = "Risk Result Completeness",
                status = "CRITICAL",
                message = "Risk orchestrator reported status ${response.status.value}",
                lastChecked = now,
            )
        }
        val readiness = lenientJson.decodeFromString<ReadinessResponseDto>(response.body())
        val totalDlq = readiness.consumers.values.sumOf { it.recordsSentToDlqTotal }
        if (totalDlq == 0L) {
            DataQualityCheckResponse(
                name = "Risk Result Completeness",
                status = "OK",
                message = "Risk orchestrator is ready, no DLQ records",
                lastChecked = now,
            )
        } else {
            DataQualityCheckResponse(
                name = "Risk Result Completeness",
                status = "WARNING",
                message = "$totalDlq risk event(s) sent to DLQ",
                lastChecked = now,
            )
        }
    } catch (e: Exception) {
        DataQualityCheckResponse(
            name = "Risk Result Completeness",
            status = "CRITICAL",
            message = "Cannot reach risk-orchestrator: ${e.message}",
            lastChecked = now,
        )
    }
}

private suspend fun buildFixSessionCheck(
    httpClient: HttpClient?,
    positionUrl: String?,
    now: String,
): DataQualityCheckResponse {
    if (httpClient == null || positionUrl == null) {
        return DataQualityCheckResponse(
            name = "FIX Connectivity",
            status = "UNKNOWN",
            message = "not configured",
            lastChecked = now,
        )
    }

    return try {
        val response = httpClient.get("$positionUrl/api/v1/fix/sessions")
        if (!response.status.isSuccess()) {
            return DataQualityCheckResponse(
                name = "FIX Connectivity",
                status = "CRITICAL",
                message = "Failed to fetch FIX session status: ${response.status.value}",
                lastChecked = now,
            )
        }
        val sessions = lenientJson.decodeFromString<List<FIXSessionDto>>(response.body())
        buildFixCheckFromSessions(sessions, now)
    } catch (e: Exception) {
        DataQualityCheckResponse(
            name = "FIX Connectivity",
            status = "CRITICAL",
            message = "Cannot reach position-service for FIX session status",
            lastChecked = now,
        )
    }
}

private fun buildFixCheckFromSessions(sessions: List<FIXSessionDto>, now: String): DataQualityCheckResponse {
    if (sessions.isEmpty()) {
        return DataQualityCheckResponse(
            name = "FIX Connectivity",
            status = "WARNING",
            message = "No FIX sessions configured",
            lastChecked = now,
        )
    }

    val disconnected = sessions.filter { it.status == "DISCONNECTED" }
    val reconnecting = sessions.filter { it.status == "RECONNECTING" }
    val connected = sessions.filter { it.status == "CONNECTED" }

    return when {
        disconnected.size == sessions.size -> DataQualityCheckResponse(
            name = "FIX Connectivity",
            status = "CRITICAL",
            message = "All ${sessions.size} FIX session(s) are DISCONNECTED",
            lastChecked = now,
        )
        disconnected.isNotEmpty() || reconnecting.isNotEmpty() -> DataQualityCheckResponse(
            name = "FIX Connectivity",
            status = "WARNING",
            message = "${connected.size}/${sessions.size} session(s) connected; " +
                "${disconnected.size} disconnected, ${reconnecting.size} reconnecting",
            lastChecked = now,
        )
        else -> DataQualityCheckResponse(
            name = "FIX Connectivity",
            status = "OK",
            message = "All ${sessions.size} FIX session(s) connected",
            lastChecked = now,
        )
    }
}
