package com.kinetix.gateway.client

import com.kinetix.common.dtos.CreatePositionNoteRequest
import com.kinetix.common.dtos.PositionNoteDto
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

    override suspend fun aggregateAllBooks(baseCurrency: String): PortfolioAggregationSummary {
        // Walk every book the position-service knows about and fold their
        // per-book summaries into a single firm-level aggregate. A bookless
        // demo seed (or a position-service that's still warming up) yields a
        // zero-aggregate rather than throwing — matches the prior stub
        // behaviour from `HierarchyRoutes.emptyAggregate`.
        val books = listPortfolios()
        if (books.isEmpty()) {
            return zeroAggregate(baseCurrency)
        }
        val perBook = books.map { getBookSummary(it.id, baseCurrency) }
        return foldFirmSummary(perBook, baseCurrency)
    }

    private fun zeroAggregate(baseCurrency: String): PortfolioAggregationSummary {
        val currency = java.util.Currency.getInstance(baseCurrency)
        val zero = com.kinetix.common.model.Money(java.math.BigDecimal.ZERO, currency)
        return PortfolioAggregationSummary(
            bookId = "firm",
            baseCurrency = baseCurrency,
            totalNav = zero,
            totalUnrealizedPnl = zero,
            currencyBreakdown = emptyList(),
        )
    }

    private fun foldFirmSummary(
        summaries: List<PortfolioAggregationSummary>,
        baseCurrency: String,
    ): PortfolioAggregationSummary {
        val baseCurrencyObj = java.util.Currency.getInstance(baseCurrency)
        val totalNav = summaries.fold(java.math.BigDecimal.ZERO) { a, s -> a + s.totalNav.amount }
        val totalPnl = summaries.fold(java.math.BigDecimal.ZERO) { a, s -> a + s.totalUnrealizedPnl.amount }
        val breakdown = summaries
            .flatMap { it.currencyBreakdown }
            .groupBy { it.currency }
            .map { (currency, exposures) ->
                val curObj = java.util.Currency.getInstance(currency)
                val local = exposures.fold(java.math.BigDecimal.ZERO) { a, e -> a + e.localValue.amount }
                val base = exposures.fold(java.math.BigDecimal.ZERO) { a, e -> a + e.baseValue.amount }
                CurrencyExposureSummary(
                    currency = currency,
                    localValue = com.kinetix.common.model.Money(local, curObj),
                    baseValue = com.kinetix.common.model.Money(base, baseCurrencyObj),
                    fxRate = exposures.first().fxRate,
                )
            }
            .sortedByDescending { it.baseValue.amount.abs() }
        return PortfolioAggregationSummary(
            bookId = "firm",
            baseCurrency = baseCurrency,
            totalNav = com.kinetix.common.model.Money(totalNav, baseCurrencyObj),
            totalUnrealizedPnl = com.kinetix.common.model.Money(totalPnl, baseCurrencyObj),
            currencyBreakdown = breakdown,
        )
    }

    override suspend fun listPositionNotes(bookId: BookId, instrumentId: String?): List<PositionNoteDto> {
        val response = httpClient.get("$baseUrl/api/v1/positions/${bookId.value}/notes") {
            if (instrumentId != null) parameter("instrumentId", instrumentId)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun createPositionNote(
        bookId: BookId,
        request: CreatePositionNoteRequest,
        author: String?,
    ): PositionNoteDto {
        val response = httpClient.post("$baseUrl/api/v1/positions/${bookId.value}/notes") {
            contentType(ContentType.Application.Json)
            if (author != null) header("X-User", author)
            setBody(request)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun deletePositionNote(id: String): Boolean {
        val response = httpClient.delete("$baseUrl/api/v1/positions/notes/$id")
        return when (response.status) {
            HttpStatusCode.NoContent -> true
            HttpStatusCode.NotFound -> false
            else -> handleErrorResponse(response)
        }
    }
}
