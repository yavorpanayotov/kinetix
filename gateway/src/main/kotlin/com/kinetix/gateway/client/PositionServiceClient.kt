package com.kinetix.gateway.client

import com.kinetix.common.dtos.CreatePositionNoteRequest
import com.kinetix.common.dtos.PositionNoteDto
import com.kinetix.common.model.*
import java.math.BigDecimal
import java.time.Instant

data class BookTradeCommand(
    val tradeId: TradeId,
    val bookId: BookId,
    val instrumentId: InstrumentId,
    val assetClass: AssetClass,
    val side: Side,
    val quantity: BigDecimal,
    val price: Money,
    val tradedAt: Instant,
    val instrumentType: String,
    val userId: String? = null,
    val userRole: String? = null,
)

data class BookTradeResult(
    val trade: Trade,
    val position: Position,
)

data class PortfolioSummary(
    val id: BookId,
)

data class CurrencyExposureSummary(
    val currency: String,
    val localValue: Money,
    val baseValue: Money,
    val fxRate: BigDecimal,
)

data class PortfolioAggregationSummary(
    val bookId: String,
    val baseCurrency: String,
    val totalNav: Money,
    val totalUnrealizedPnl: Money,
    val currencyBreakdown: List<CurrencyExposureSummary>,
)

data class TradeHistoryPage(
    val items: List<Trade>,
    val total: Long,
    val offset: Long,
    val limit: Int,
    val hasMore: Boolean,
)

interface PositionServiceClient {
    suspend fun listPortfolios(): List<PortfolioSummary>
    suspend fun bookTrade(command: BookTradeCommand): BookTradeResult
    suspend fun getPositions(bookId: BookId): List<Position>
    suspend fun getTradeHistory(bookId: BookId): List<Trade>
    suspend fun getTradeHistoryPage(
        bookId: BookId,
        offset: Long,
        limit: Int,
        counterpartyId: String? = null,
    ): TradeHistoryPage
    suspend fun getBookSummary(bookId: BookId, baseCurrency: String): PortfolioAggregationSummary

    /**
     * Firm-level aggregation across every book the position-service knows
     * about. Used by `GET /api/v1/firm/summary` so the default Positions tab
     * shows a real NAV instead of a $0 stub when the user hasn't drilled into
     * a specific book yet.
     */
    suspend fun aggregateAllBooks(baseCurrency: String): PortfolioAggregationSummary

    /** Lists position notes for a book, optionally filtered to a single instrument. */
    suspend fun listPositionNotes(bookId: BookId, instrumentId: String? = null): List<PositionNoteDto>

    /** Creates a position note. The [author] is forwarded to the upstream via X-User. */
    suspend fun createPositionNote(
        bookId: BookId,
        request: CreatePositionNoteRequest,
        author: String?,
    ): PositionNoteDto

    /** Returns true when a note was removed, false when no row matched the id. */
    suspend fun deletePositionNote(id: String): Boolean
}
