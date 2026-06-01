package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.BacktestRequest
import com.kinetix.demo.client.dtos.BacktestResponse
import com.kinetix.demo.client.dtos.BacktestResult
import com.kinetix.demo.client.dtos.CreateSubmissionRequest
import com.kinetix.demo.client.dtos.SubmissionRef
import com.kinetix.demo.client.dtos.SubmissionResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Ktor-based [RegulatoryServiceClient] implementation. The [httpClient] is
 * injected so unit tests can swap in `MockEngine`; the production wiring uses
 * the CIO engine.
 *
 * Errors are fail-loud: any non-2xx response from regulatory-service throws
 * [IllegalStateException] including the HTTP status and a short body excerpt,
 * so the demo orchestrator surfaces wiring problems immediately rather than
 * silently skipping the regulatory step.
 */
class RegulatoryServiceHttpClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : RegulatoryServiceClient {

    private val logger = LoggerFactory.getLogger(RegulatoryServiceHttpClient::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun runBacktest(bookId: String, request: BacktestRequest): BacktestResult {
        val url = "$baseUrl/api/v1/regulatory/backtest/$bookId"
        val body = json.encodeToString(BacktestRequest.serializer(), request)
        logger.debug(
            "Running backtest bookId={} days={} confidenceLevel={} calculationType={}",
            bookId,
            request.dailyVarPredictions.size,
            request.confidenceLevel,
            request.calculationType,
        )
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
        val responseBody = response.bodyAsText()
        val parsed = try {
            json.decodeFromString(BacktestResponse.serializer(), responseBody)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode BacktestResponse from $url: body=${responseBody.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
        return BacktestResult(
            violationCount = parsed.violationCount,
            kupiecPass = parsed.kupiecPass,
            trafficLightZone = parsed.trafficLightZone,
        )
    }

    override suspend fun createSubmission(request: CreateSubmissionRequest): SubmissionRef {
        val url = "$baseUrl/api/v1/submissions"
        val body = json.encodeToString(CreateSubmissionRequest.serializer(), request)
        logger.debug(
            "Creating submission reportType={} preparerId={} deadline={}",
            request.reportType,
            request.preparerId,
            request.deadline,
        )
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
        val responseBody = response.bodyAsText()
        val parsed = try {
            json.decodeFromString(SubmissionResponse.serializer(), responseBody)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode SubmissionResponse from $url: body=${responseBody.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
        return SubmissionRef(
            id = parsed.id,
            reportType = parsed.reportType,
            status = parsed.status,
        )
    }

    override suspend fun calculateFrtb(bookId: String) {
        val url = "$baseUrl/api/v1/regulatory/frtb/$bookId/calculate"
        logger.debug("Triggering FRTB calculation bookId={}", bookId)
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
        // Result body discarded — regulatory-service has persisted the record as
        // the new "latest" for the book, which is all the demo needs.
    }

    private suspend fun failLoudly(method: String, url: String, response: HttpResponse): Nothing {
        val excerpt = try {
            response.bodyAsText().take(BODY_EXCERPT_LIMIT)
        } catch (_: Exception) {
            ""
        }
        throw IllegalStateException(
            "regulatory-service $method $url returned ${response.status.value}: $excerpt",
        )
    }

    private companion object {
        const val BODY_EXCERPT_LIMIT = 200
    }
}
