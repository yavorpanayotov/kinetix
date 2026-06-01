package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.BookExposureSnapshot
import com.kinetix.demo.client.dtos.CreateRiskBudgetRequest
import com.kinetix.demo.client.dtos.EodPromotionResponseDto
import com.kinetix.demo.client.dtos.EodTimelineResponse
import com.kinetix.demo.client.dtos.HierarchyRiskResponse
import com.kinetix.demo.client.dtos.PaginatedJobsResponseDto
import com.kinetix.demo.client.dtos.PromoteEodRequest
import com.kinetix.demo.client.dtos.SodBaselineStatusDto
import com.kinetix.demo.client.dtos.CrossBookVaRRequestBody
import com.kinetix.demo.client.dtos.StressTestBatchRequestBody
import com.kinetix.demo.client.dtos.VaRCalculationRequestBody
import com.kinetix.demo.client.dtos.ValuationJobSummary
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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

    override suspend fun eodTimeline(
        bookId: String,
        from: LocalDate,
        to: LocalDate,
    ): EodTimelineResponse {
        val url = "$baseUrl/api/v1/risk/eod-timeline/$bookId?from=$from&to=$to"
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            failLoudly("GET", url, response)
        }
        val body = response.bodyAsText()
        return try {
            json.decodeFromString(EodTimelineResponse.serializer(), body)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode EodTimelineResponse from $url: body=${body.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
    }

    override suspend fun calculateVaR(bookId: String) {
        val url = "$baseUrl/api/v1/risk/var/$bookId"
        val request = VaRCalculationRequestBody(
            calculationType = "PARAMETRIC",
            confidenceLevel = "CL_95",
            timeHorizonDays = "1",
            numSimulations = "10000",
        )
        val body = json.encodeToString(VaRCalculationRequestBody.serializer(), request)
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
        // We deliberately discard the VaR response body — the job ID is
        // retrieved via findLatestCompletedJob, and the VaR figures will be
        // observed downstream through the EOD timeline read path.
    }

    override suspend fun calculateVaRWithParams(
        bookId: String,
        confidenceLevel: String,
        horizonDays: Int,
        method: String,
        valuationDate: java.time.LocalDate,
    ) {
        val url = "$baseUrl/api/v1/risk/var/$bookId"
        val request = VaRCalculationRequestBody(
            calculationType = method,
            confidenceLevel = confidenceLevel,
            timeHorizonDays = horizonDays.toString(),
            numSimulations = "10000",
        )
        val body = json.encodeToString(VaRCalculationRequestBody.serializer(), request)
        logger.debug(
            "Bootstrap VaR: bookId={} method={} confidenceLevel={} horizonDays={} valuationDate={}",
            bookId, method, confidenceLevel, horizonDays, valuationDate,
        )
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
    }

    override suspend fun findLatestCompletedJob(bookId: String): ValuationJobSummary? {
        val url = "$baseUrl/api/v1/risk/jobs/$bookId?limit=1&offset=0"
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            failLoudly("GET", url, response)
        }
        val body = response.bodyAsText()
        val parsed = try {
            json.decodeFromString(PaginatedJobsResponseDto.serializer(), body)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode PaginatedJobsResponse from $url: body=${body.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
        return parsed.items.firstOrNull { it.status == "COMPLETED" }
            ?: parsed.items.firstOrNull()
    }

    override suspend fun findOfficialEod(bookId: String, valuationDate: LocalDate): EodPromotionResponseDto? {
        val url = "$baseUrl/api/v1/risk/jobs/$bookId/official-eod?date=$valuationDate"
        val response = httpClient.get(url)
        if (response.status == HttpStatusCode.NotFound) {
            return null
        }
        if (!response.status.isSuccess()) {
            failLoudly("GET", url, response)
        }
        val body = response.bodyAsText()
        return try {
            json.decodeFromString(EodPromotionResponseDto.serializer(), body)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode EodPromotionResponseDto from $url: body=${body.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
    }

    override suspend fun promoteJobToOfficialEod(jobId: String, promotedBy: String): EodPromotionResponseDto {
        val url = "$baseUrl/api/v1/risk/jobs/$jobId/label"
        val request = PromoteEodRequest(label = OFFICIAL_EOD_LABEL, promotedBy = promotedBy)
        val body = json.encodeToString(PromoteEodRequest.serializer(), request)
        val response = httpClient.patch(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            failLoudly("PATCH", url, response)
        }
        val responseBody = response.bodyAsText()
        return try {
            json.decodeFromString(EodPromotionResponseDto.serializer(), responseBody)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode EodPromotionResponseDto from $url: body=${responseBody.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
    }

    override suspend fun getSodBaselineStatus(bookId: String): SodBaselineStatusDto {
        val url = "$baseUrl/api/v1/risk/sod-snapshot/$bookId/status"
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            failLoudly("GET", url, response)
        }
        val body = response.bodyAsText()
        return try {
            json.decodeFromString(SodBaselineStatusDto.serializer(), body)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode SodBaselineStatusDto from $url: body=${body.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
    }

    override suspend fun createSodSnapshot(bookId: String): SodBaselineStatusDto {
        val url = "$baseUrl/api/v1/risk/sod-snapshot/$bookId"
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
        val body = response.bodyAsText()
        return try {
            json.decodeFromString(SodBaselineStatusDto.serializer(), body)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode SodBaselineStatusDto from $url: body=${body.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
    }

    override suspend fun calculateCrossBookVaR(
        bookIds: List<String>,
        portfolioGroupId: String,
        confidenceLevel: String,
        horizonDays: Int,
        method: String,
    ) {
        val url = "$baseUrl/api/v1/risk/var/cross-book"
        val request = CrossBookVaRRequestBody(
            bookIds = bookIds,
            portfolioGroupId = portfolioGroupId,
            calculationType = method,
            confidenceLevel = confidenceLevel,
            timeHorizonDays = horizonDays.toString(),
            numSimulations = "10000",
        )
        val body = json.encodeToString(CrossBookVaRRequestBody.serializer(), request)
        logger.debug(
            "Cross-book VaR aggregate: portfolioGroupId={} bookCount={} method={} confidenceLevel={} horizonDays={}",
            portfolioGroupId, bookIds.size, method, confidenceLevel, horizonDays,
        )
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
        // Result body is discarded — the aggregate is now cached in
        // risk-orchestrator under portfolioGroupId and accessible via GET.
    }

    override suspend fun runCannedStressScenario(bookId: String, scenarioName: String) {
        val url = "$baseUrl/api/v1/risk/stress/$bookId/canned/$scenarioName"
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
        // Result body discarded — the canned result is cached server-side and
        // read by the UI via the matching GET endpoint.
    }

    override suspend fun runAllStressScenarios(bookId: String) {
        // 1. List the registered scenarios — same source the UI's scenario
        //    picker reads. An empty set means there is nothing to sweep.
        val scenariosUrl = "$baseUrl/api/v1/risk/stress/scenarios"
        val scenariosResponse = httpClient.get(scenariosUrl)
        if (!scenariosResponse.status.isSuccess()) {
            failLoudly("GET", scenariosUrl, scenariosResponse)
        }
        val scenariosBody = scenariosResponse.bodyAsText()
        val scenarioNames = try {
            json.decodeFromString(ListSerializer(String.serializer()), scenariosBody)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode scenario list from $scenariosUrl: body=${scenariosBody.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
        if (scenarioNames.isEmpty()) {
            logger.warn("No stress scenarios registered — skipping batch sweep for book {}", bookId)
            return
        }

        // 2. Fire the full batch — exactly what "Run All Scenarios" does — so the
        //    Scenarios tab has a latest run to render (kx-kjse).
        val batchUrl = "$baseUrl/api/v1/risk/stress/$bookId/batch"
        val request = StressTestBatchRequestBody(
            scenarioNames = scenarioNames,
            calculationType = "PARAMETRIC",
            confidenceLevel = "CL_95",
            timeHorizonDays = "1",
        )
        val batchBody = json.encodeToString(StressTestBatchRequestBody.serializer(), request)
        val batchResponse = httpClient.post(batchUrl) {
            contentType(ContentType.Application.Json)
            setBody(batchBody)
        }
        if (!batchResponse.status.isSuccess()) {
            failLoudly("POST", batchUrl, batchResponse)
        }
        // Result body discarded — the demo orchestrator only needs the sweep to
        // have happened.
    }

    override suspend fun triggerKrdSnapshot(bookId: String) {
        val url = "$baseUrl/api/v1/risk/krd/$bookId"
        logger.debug("Triggering KRD computation bookId={}", bookId)
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            failLoudly("GET", url, response)
        }
        // Body discarded — risk-orchestrator has computed KRD from the book's
        // live fixed-income positions, which is what the UI's KRD view reads.
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
        const val OFFICIAL_EOD_LABEL = "OFFICIAL_EOD"
    }
}
