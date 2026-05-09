package com.kinetix.position.persistence

import com.kinetix.common.model.BookId
import com.kinetix.common.model.Trade
import com.kinetix.common.model.TradeId
import com.kinetix.common.model.TradeStatus
import java.time.Instant

interface TradeEventRepository {
    suspend fun save(trade: Trade)
    suspend fun findByTradeId(tradeId: TradeId): Trade?
    suspend fun findByBookId(bookId: BookId): List<Trade>
    suspend fun findByBookIdInRange(bookId: BookId, from: Instant, to: Instant): List<Trade>
    suspend fun updateStatus(tradeId: TradeId, status: TradeStatus)
    suspend fun countSince(since: Instant): Long

    /**
     * Server-side pagination for the trade blotter. Returns a slice of trades for the
     * book, ordered by tradedAt DESC then tradeId for stable pagination. Supports
     * filtering by counterpartyId. The total count of matching rows is returned
     * separately so the UI can render "page N of M" without a second round-trip.
     */
    suspend fun findByBookIdPaged(
        bookId: BookId,
        offset: Long,
        limit: Int,
        counterpartyId: String? = null,
    ): List<Trade>

    suspend fun countByBookId(
        bookId: BookId,
        counterpartyId: String? = null,
    ): Long

    /**
     * Returns all trades (across all books) booked against the given counterparty.
     * Used to derive per-counterparty netting-set membership.
     */
    suspend fun findByCounterpartyId(counterpartyId: String): List<Trade>

    /**
     * Demo-mode bulk insert for the 252-day seed trade tape. Bypasses the per-trade
     * Kafka publish + audit chain — the booking path is exercised by acceptance tests,
     * not the seeder. Production callers must continue to route through
     * TradeBookingService.handle().
     */
    suspend fun bulkInsertForSeed(trades: List<Trade>)
}
