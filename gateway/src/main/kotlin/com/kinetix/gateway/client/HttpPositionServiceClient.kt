package com.kinetix.gateway.client

import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.common.model.Trade
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class HttpPositionServiceClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : PositionServiceClient {

    @Serializable
    private data class UpstreamErrorBody(val code: String = "", val message: String = "")

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private suspend fun handleErrorResponse(response: HttpResponse): Nothing {
        val body = try {
            response.bodyAsText()
        } catch (_: Exception) {
            ""
        }

        val message = try {
            lenientJson.decodeFromString<UpstreamErrorBody>(body).message
        } catch (_: Exception) {
            body.ifBlank { response.status.description }
        }

        when (response.status.value) {
            503 -> {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toIntOrNull()
                throw ServiceUnavailableException(retryAfter, message)
            }
            504 -> throw GatewayTimeoutException(message)
            else -> throw UpstreamErrorException(response.status.value, message)
        }
    }

    override suspend fun listPortfolios(): List<PortfolioSummary> {
        val response = httpClient.get("$baseUrl/api/v1/books")
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dtos: List<BookSummaryDto> = response.body()
        return dtos.map { it.toDomain() }
    }

    override suspend fun bookTrade(command: BookTradeCommand): BookTradeResult {
        val response = httpClient.post("$baseUrl/api/v1/books/${command.bookId.value}/trades") {
            contentType(ContentType.Application.Json)
            setBody(
                BookTradeRequestDto(
                    tradeId = command.tradeId.value,
                    instrumentId = command.instrumentId.value,
                    assetClass = command.assetClass.name,
                    side = command.side.name,
                    quantity = command.quantity.toPlainString(),
                    priceAmount = command.price.amount.toPlainString(),
                    priceCurrency = command.price.currency.currencyCode,
                    tradedAt = command.tradedAt.toString(),
                    instrumentType = command.instrumentType,
                    userId = command.userId,
                    userRole = command.userRole,
                )
            )
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: BookTradeResponseDto = response.body()
        return dto.toDomain()
    }

    override suspend fun getPositions(bookId: BookId): List<Position> {
        val response = httpClient.get("$baseUrl/api/v1/books/${bookId.value}/positions")
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dtos: List<PositionDto> = response.body()
        return dtos.map { it.toDomain() }
    }

    override suspend fun getTradeHistory(bookId: BookId): List<Trade> {
        val response = httpClient.get("$baseUrl/api/v1/books/${bookId.value}/trades")
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dtos: List<TradeDto> = response.body()
        return dtos.map { it.toDomain() }
    }

    override suspend fun getTradeHistoryPage(
        bookId: BookId,
        offset: Long,
        limit: Int,
        counterpartyId: String?,
    ): TradeHistoryPage {
        val response = httpClient.get("$baseUrl/api/v1/books/${bookId.value}/trades/page") {
            parameter("offset", offset)
            parameter("limit", limit)
            if (counterpartyId != null) parameter("counterpartyId", counterpartyId)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: TradeHistoryPageDto = response.body()
        return TradeHistoryPage(
            items = dto.items.map { it.toDomain() },
            total = dto.total,
            offset = dto.offset,
            limit = dto.limit,
            hasMore = dto.hasMore,
        )
    }

    override suspend fun getBookSummary(bookId: BookId, baseCurrency: String): PortfolioAggregationSummary {
        val response = httpClient.get("$baseUrl/api/v1/books/${bookId.value}/summary") {
            parameter("baseCurrency", baseCurrency)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: PortfolioAggregationDto = response.body()
        return dto.toDomain()
    }
}
