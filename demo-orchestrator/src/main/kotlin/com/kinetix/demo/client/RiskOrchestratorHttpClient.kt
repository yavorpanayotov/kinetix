package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.BookExposureSnapshot
import com.kinetix.demo.client.dtos.CreateRiskBudgetRequest
import com.kinetix.demo.client.dtos.HierarchyRiskResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Ktor-based [RiskOrchestratorClient] implementation. The [httpClient] is
 * injected so unit tests can swap in `MockEngine`; the production wiring uses
 * the CIO engine.
 *
 * Errors are fail-loud: any non-2xx response from risk-orchestrator throws
 * [IllegalStateException] including the HTTP status and a short body excerpt,
 * so the demo orchestrator surfaces wiring problems immediately rather than
 * silently seeding nothing.
 */
class RiskOrchestratorHttpClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : RiskOrchestratorClient {

    private val logger = LoggerFactory.getLogger(RiskOrchestratorHttpClient::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun readBookExposure(bookId: String): BookExposureSnapshot {
        val url = "$baseUrl/api/v1/risk/hierarchy/BOOK/$bookId"
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            failLoudly("GET", url, response)
        }
        val body = response.bodyAsText()
        val parsed = try {
            json.decodeFromString(HierarchyRiskResponse.serializer(), body)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode HierarchyRiskResponse from $url: body=${body.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
        val varValue = parsed.varValue.toBigDecimalOrNull()
            ?: throw IllegalStateException(
                "Risk-orchestrator returned non-numeric varValue='${parsed.varValue}' for book $bookId",
            )
        return BookExposureSnapshot(
            bookId = parsed.entityId,
            varValue = varValue,
            absoluteDelta = null, // hierarchy endpoint does not expose abs-delta today
        )
    }

    override suspend fun seedLimit(bookId: String, limitType: LimitType, threshold: BigDecimal) {
        val url = "$baseUrl/api/v1/risk/budgets"
        val request = CreateRiskBudgetRequest(
            entityLevel = "BOOK",
            entityId = bookId,
            budgetType = limitType.name,
            budgetPeriod = "DAILY",
            budgetAmount = threshold.toPlainString(),
            effectiveFrom = LocalDate.now(ZoneOffset.UTC).toString(),
            allocatedBy = "demo-orchestrator",
        )
        val body = json.encodeToString(CreateRiskBudgetRequest.serializer(), request)
        logger.debug("Seeding limit bookId={} type={} threshold={}", bookId, limitType, threshold)
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
    }

    private suspend fun failLoudly(method: String, url: String, response: HttpResponse): Nothing {
        val excerpt = try {
            response.bodyAsText().take(BODY_EXCERPT_LIMIT)
        } catch (_: Exception) {
            ""
        }
        throw IllegalStateException(
            "risk-orchestrator $method $url returned ${response.status.value}: $excerpt",
        )
    }

    private companion object {
        const val BODY_EXCERPT_LIMIT = 200
    }
}
