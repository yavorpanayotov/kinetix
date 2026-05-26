package com.kinetix.demo.client

import com.kinetix.demo.client.dtos.PrimeBrokerStatementRequest
import com.kinetix.demo.client.dtos.RecordExecutionCostRequest
import com.kinetix.demo.client.dtos.StrategyListItemResponse
import com.kinetix.demo.client.dtos.StrategyTradeRequest
import com.kinetix.demo.client.dtos.StrategyTradeResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Ktor-based [PositionServiceClient] implementation. The [httpClient] is
 * injected so unit tests can swap in `MockEngine`; the production wiring uses
 * the CIO engine.
 *
 * Errors are fail-loud: any non-2xx response from position-service throws
 * [IllegalStateException] including the HTTP status and a short body excerpt,
 * so the demo orchestrator surfaces wiring problems immediately rather than
 * silently dropping trades.
 */
class PositionServiceHttpClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : PositionServiceClient {

    private val logger = LoggerFactory.getLogger(PositionServiceHttpClient::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override suspend fun bookTrade(
        bookId: String,
        strategyId: String,
        request: StrategyTradeRequest,
    ): String {
        val url = "$baseUrl/api/v1/books/$bookId/strategies/$strategyId/trades"
        val body = json.encodeToString(StrategyTradeRequest.serializer(), request)
        logger.debug(
            "Booking trade bookId={} strategyId={} instrumentId={} side={} quantity={}",
            bookId,
            strategyId,
            request.instrumentId,
            request.side,
            request.quantity,
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
            json.decodeFromString(StrategyTradeResponse.serializer(), responseBody)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode StrategyTradeResponse from $url: body=${responseBody.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
        return parsed.tradeId
    }

    override suspend fun recordExecutionCost(
        bookId: String,
        request: RecordExecutionCostRequest,
    ) {
        val url = "$baseUrl/api/v1/internal/execution/cost/$bookId"
        val body = json.encodeToString(RecordExecutionCostRequest.serializer(), request)
        logger.debug(
            "Recording execution cost bookId={} orderId={} instrumentId={} slippageBps={}",
            bookId,
            request.orderId,
            request.instrumentId,
            request.slippageBps,
        )
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
    }

    override suspend fun uploadPrimeBrokerStatement(
        bookId: String,
        request: PrimeBrokerStatementRequest,
    ) {
        val url = "$baseUrl/api/v1/execution/reconciliation/$bookId/statements"
        val body = json.encodeToString(PrimeBrokerStatementRequest.serializer(), request)
        logger.debug(
            "Uploading prime broker statement bookId={} date={} positions={}",
            bookId,
            request.date,
            request.positions.size,
        )
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            failLoudly("POST", url, response)
        }
    }

    override suspend fun listStrategies(bookId: String): List<String> {
        val url = "$baseUrl/api/v1/books/$bookId/strategies"
        logger.debug("Listing strategies for bookId={}", bookId)
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            failLoudly("GET", url, response)
        }
        val responseBody = response.bodyAsText()
        val items = try {
            json.decodeFromString(ListSerializer(StrategyListItemResponse.serializer()), responseBody)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode strategy list from $url: body=${responseBody.take(BODY_EXCERPT_LIMIT)}",
                e,
            )
        }
        return items.map { it.strategyId }
    }

    private suspend fun failLoudly(method: String, url: String, response: HttpResponse): Nothing {
        val excerpt = try {
            response.bodyAsText().take(BODY_EXCERPT_LIMIT)
        } catch (_: Exception) {
            ""
        }
        throw IllegalStateException(
            "position-service $method $url returned ${response.status.value}: $excerpt",
        )
    }

    private companion object {
        const val BODY_EXCERPT_LIMIT = 200
    }
}
